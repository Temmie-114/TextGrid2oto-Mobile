# -*- coding: utf-8 -*-
"""Android 端任务编排层。

Kotlin 侧只调用三个入口：

* ``run_job(payload_json) -> str``  执行一个任务，返回 JSON 结果
* ``request_cancel()``              请求中止当前任务
* ``set_sink(obj)``                 注入日志接收器（Java 对象，需有 log/progress 方法）

原项目的大量 ``print`` 通过重定向 sys.stdout 接到日志栏，所以进度文本与
桌面版一致。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys
import threading
import time
import traceback
from pathlib import Path

# 原项目的子目录使用顶层绝对导入（如 lab_generate/index2lab.py 里的
# `from lab_generate import wavname2lab`）。移植后这些目录都收在 tg2oto/ 下，
# 因此必须把 tg2oto/ 本身也放进 sys.path，否则运行时会报
# ModuleNotFoundError: No module named 'lab_generate'。
_HERE = os.path.dirname(os.path.abspath(__file__))
for _path in (_HERE, os.path.dirname(_HERE)):
    if _path and _path not in sys.path:
        sys.path.insert(0, _path)

_cancel = threading.Event()
_sink = None
_lock = threading.Lock()

_PROGRESS_RE = re.compile(r"(?:Processing|处理)\s*(\d+)\s*/\s*(\d+)")
_BRACKET_RE = re.compile(r"\[(\d+)/(\d+)\]")


# ---------------------------------------------------------------------------
# 日志 / 进度
# ---------------------------------------------------------------------------

def set_sink(obj):
    global _sink
    _sink = obj


def request_cancel():
    _cancel.set()


def is_cancelled() -> bool:
    return _cancel.is_set()


def log(message: str):
    text = str(message)
    # 进度解析放在这里：TIFA 的进度是经 progress_callback=log 直接回调的，
    # 不经过 stdout 捕获，原来只挂在 _SinkStream 上，所以进度条一直不动。
    _progress_message(text)
    if _sink is not None:
        try:
            _sink.log(text)
            return
        except Exception:
            pass
    print(text)


def _progress_message(text: str):
    if _sink is None:
        return
    for pattern in (_PROGRESS_RE, _BRACKET_RE):
        m = pattern.search(text)
        if m:
            try:
                cur, total = int(m.group(1)), int(m.group(2))
                if total > 0:
                    _sink.progress(min(1.0, cur / total))
                return
            except Exception:
                return


class _JobCancelled(Exception):
    pass


class _SinkStream(io.TextIOBase):
    """把写入按行转发到日志栏；同时兼容 ``print(..., end='')`` 的连续输出。"""

    def __init__(self):
        self._buf = ""
        self._last_emit = time.time()

    def writable(self):
        return True

    def write(self, s):
        if not s:
            return 0
        self._buf += s
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            self._emit(line)
        if len(self._buf) > 0 and time.time() - self._last_emit > 0.4:
            self._emit(self._buf)
            self._buf = ""
        return len(s)

    def flush(self):
        if self._buf:
            self._emit(self._buf)
            self._buf = ""

    def _emit(self, line):
        self._last_emit = time.time()
        line = line.rstrip("\r")
        if not line.strip():
            return
        log(line)


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def _apply_threads(params):
    """把界面上的线程数下发给 Kotlin 的 OrtEngine（0/缺省 = 自动）。"""
    try:
        n = int(params.get("threads", 0) or 0)
    except Exception:
        n = 0
    if n <= 0:
        return
    try:
        from tg2oto import ort_shim
        ort_shim.set_threads(n)
        log(f"推理线程数：{n}")
    except Exception as exc:
        log(f"设置线程数失败：{exc}")


def run_job(payload_json: str) -> str:
    params = json.loads(payload_json)
    kind = params.get("kind")
    _cancel.clear()
    _apply_threads(params)

    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout = _SinkStream()
    sys.stderr = _SinkStream()
    started = time.time()
    try:
        handler = _HANDLERS.get(kind)
        if handler is None:
            raise ValueError(f"未知任务类型: {kind}")
        log(f"▶ 开始：{params.get('title') or kind}")
        handler(params)
        elapsed = time.time() - started
        log(f"✔ 完成，耗时 {elapsed:.1f}s")
        if _sink is not None:
            _sink.progress(1.0)
        return json.dumps({"ok": True, "elapsed": elapsed}, ensure_ascii=False)
    except _JobCancelled:
        log("⏹ 已中止")
        return json.dumps({"ok": False, "cancelled": True}, ensure_ascii=False)
    except Exception as exc:
        tb = traceback.format_exc()
        log(tb)
        return json.dumps({"ok": False, "error": str(exc), "traceback": tb}, ensure_ascii=False)
    finally:
        sys.stdout.flush()
        sys.stderr.flush()
        sys.stdout, sys.stderr = old_out, old_err


class _StopEvent:
    """同时监听 Python 侧标志与 Kotlin 侧 JobControl，避免跨线程抢 GIL。"""

    def is_set(self):
        if _cancel.is_set():
            return True
        try:
            from java import jclass
            if jclass("com.tg2oto.mobile.core.JobControl").isCancelled():
                _cancel.set()
                return True
        except Exception:
            pass
        return False

    def wait(self, timeout=None):
        deadline = time.time() + (timeout or 0)
        while True:
            if self.is_set():
                return True
            if timeout is not None and time.time() >= deadline:
                return False
            time.sleep(0.05)

    def set(self):
        _cancel.set()

    def clear(self):
        _cancel.clear()


_stop = _StopEvent()


def _check_cancel():
    if _stop.is_set():
        raise _JobCancelled()


# ---------------------------------------------------------------------------
# 1. 生成 LAB
# ---------------------------------------------------------------------------

def _normalize_cuts(raw):
    """把 UI 传来的分隔符统一成非空字符串列表。

    中文输入法很容易打出全角逗号（``，``）或顿号（``、``），只按半角逗号切
    会整串当成一个分隔符，结果文件名里的 ``_`` 一个都没被替换掉——这就是
    生成出 ``SP _ん ...`` 的原因。这里把所有常见分隔标点都当分隔符处理。
    """
    if isinstance(raw, str):
        raw = re.split(r"[,，、;；|/\s]+", raw)
    items = []
    for c in (raw or []):
        c = str(c).strip().replace("，", ",").replace("、", ",")
        if c:
            items.append(c)
    # 万一传进来的是 "_，-" 这种整串（全角逗号没被切），按字符拆开
    if len(items) == 1 and len(items[0]) > 1 and not any(ch.isalnum() for ch in items[0]):
        items = [ch for ch in items[0] if not ch.isspace()]
    # 下划线与连字符是这类音素库最通用的两个，没列也补上（与 fallback 一致）
    for c in ("_", "-"):
        if c not in items:
            items.append(c)
    return items


def _job_lab(p):
    from tg2oto.lab_generate import wavname2lab, index2lab

    folder = p["folder"]
    if not os.path.isdir(folder):
        raise FileNotFoundError(f"文件夹不存在: {folder}")
    cuts = _normalize_cuts(p.get("cuts"))
    log(f"文件名分隔符: {', '.join(cuts)}")
    drop_r = bool(p.get("drop_r", False))

    index_path = (p.get("index_path") or "").strip()
    if index_path:
        index2lab.run(folder, index_path, cuts, drop_r=drop_r)
    else:
        wavname2lab.run(folder, cuts, auto_sp=bool(p.get("auto_sp", True)), drop_r=drop_r)
    log(f"LAB 已写入 {folder}")


# ---------------------------------------------------------------------------
# 2. 强制对齐 → TextGrid
# ---------------------------------------------------------------------------

def _resolve_dictionary(model_dir: str, language: str, given: str) -> str:
    if given:
        return given
    candidates = {
        "zh": ["zh.txt", "ds-zh-pinyin.txt", "ds-zh-pinyin-lite.txt"],
        "en": ["en-arpasing.txt"],
        "ja": ["ja-hira-假名.txt", "ja-romaji-罗马音.txt"],
        "yue": ["jyutping_dict.txt"],
    }.get(language, [])
    for name in candidates:
        path = os.path.join(model_dir, name)
        if os.path.isfile(path):
            return path
    raise FileNotFoundError(f"{model_dir} 下找不到 {language} 的字典文件")


def _lab_tokens(wav_dir):
    """收集所有 .lab 里的音素，用来判断哪本字典真正适配这批素材。"""
    tokens = set()
    for name in os.listdir(wav_dir):
        if not name.lower().endswith(".lab"):
            continue
        try:
            text = open(os.path.join(wav_dir, name), encoding="utf-8", errors="ignore").read()
        except Exception:
            continue
        for tok in text.split():
            if tok not in ("SP", "R", "sil", "pau", ""):
                tokens.add(tok)
    return tokens


def _pick_dictionary(wav_dir, model_dir, language):
    """按 lab 里的实际音素挑字典：用户没指定时，不比"语言"这一项，比覆盖率。"""
    from pathlib import Path

    from tg2oto.inference.tifa_infer import TifaInference

    tokens = _lab_tokens(wav_dir)
    if not tokens:
        return _resolve_dictionary(model_dir, language, "")

    broken = sorted(t for t in tokens if t.startswith("_") or "-" in t)
    if broken:
        log("[对齐] 检测到 lab 里有未处理的分隔符音素，例如 " + ", ".join(broken[:3])
            + " —— 请先回到「标注」页重新生成 LAB，否则对齐结果不可信")

    order = [language] + [lang for lang in ("ja", "zh", "en", "yue") if lang != language]
    best_path, best_lang, best_ratio = None, language, 0.0
    for lang in order:
        try:
            path = _resolve_dictionary(model_dir, lang, "")
        except FileNotFoundError:
            continue
        dictionary = TifaInference._load_dictionary(Path(path))
        hit = sum(1 for tok in tokens if tok in dictionary)
        ratio = hit / len(tokens)
        if ratio > best_ratio:
            best_path, best_lang, best_ratio = path, lang, ratio

    if best_path is None:
        return _resolve_dictionary(model_dir, language, "")
    if best_lang != language:
        log(f"[对齐] lab 音素与 {best_lang} 字典更匹配（覆盖 {best_ratio:.0%}），"
            f"已自动改用 {os.path.basename(best_path)}")
    elif best_ratio < 0.5:
        log(f"[对齐] 警告：{os.path.basename(best_path)} 对这批音素的覆盖率仅 {best_ratio:.0%}，"
            f"请检查语言是否选对")
    return best_path


def _check_textgrid_prefix(wav_dir):
    """兜底校验：导出 TextGrid 的音素不该带语言前缀（ja/y）。

    两套引擎导出前都会剥前缀（HFA 走 ``clear_language_prefix``，TIFA 在
    ``_encode_text`` 里剥），这里再扫一遍产物。一旦真出现斜杠音素，
    下游 JSON/oto 拿到的 alias 就是错的，必须在日志里明确报出来。
    """
    bad = []
    short = []
    for name in os.listdir(wav_dir):
        if not name.lower().endswith(".textgrid"):
            continue
        try:
            text = open(os.path.join(wav_dir, name), encoding="utf-8", errors="ignore").read()
        except Exception:
            continue
        for tok in re.findall(r'text = "([^"]*)"', text):
            if "/" in tok:
                bad.append(name)
                break
        # 顺带查「被跳过」的音素：Viterbi 允许跳过某个音素并付 skip_penalty，
        # 跳过的音素只剩极短区间（甚至 0），切出来听起来就是旁边那个音。
        spans = re.findall(
            r'xmin = ([0-9.]+)\s+xmax = ([0-9.]+)\s+text = "([^"]*)"', text, re.S
        )
        short_n = sum(1 for a, b_, _t in spans
                      if len(spans) > 2 and float(b_) - float(a) < 0.02
                      and _t.upper() not in ("SP", "AP", ""))
        if spans and short_n >= 3 and short_n / len(spans) > 0.2:
            short.append((name, short_n, len(spans)))
    if short:
        worst = max(short, key=lambda x: x[1])
        log(f"[对齐] ⚠ 有 {len(short)} 个文件出现大量超短音素（最短的 {worst[0]}："
            f"{worst[1]}/{worst[2]} 个 < 20ms），这些音素多半被 Viterbi 跳过了，"
            f"切开后听起来会是相邻的音。把「跳过惩罚」调大到 1.5~2 再对齐一次。")
    if bad:
        log(f"[对齐] 警告：{len(bad)} 个 TextGrid 的音素仍带语言前缀（如 ja/y），"
            f"下游 JSON/oto 会出错，请把这条日志反馈")
    else:
        log("[对齐] 已确认输出音素不带语言前缀，可直接进入 JSON 环节")


def _job_align(p):
    wav_dir = p["wav_dir"]
    model_dir = p["model_dir"]
    language = p.get("language", "ja")
    given = (p.get("dictionary") or "").strip()
    dictionary = given if given else _pick_dictionary(wav_dir, model_dir, language)
    engine = p.get("engine", "hubertfa")

    if engine == "tifa":
        from tg2oto.inference import tifa_infer

        inf = tifa_infer.TifaInference(Path(model_dir))
        inf.load_config()
        inf.load_model("cpu")
        inf.set_progress_callback(log)
        inf.get_dataset(wav_dir, language=language, g2p="dictionary",
                        dictionary_path=dictionary, in_format="lab")
        inf.infer(batch_size=int(p.get("batch_size", 2)),
                  skip_penalty=float(p.get("skip_penalty", 0.5)),
                  stop_event=_stop)
        inf.export(wav_dir)
        _check_textgrid_prefix(wav_dir)
        return

    from tg2oto.inference import onnx_infer

    model_path = Path(model_dir) / "model.onnx"
    if not model_path.is_file():
        raise FileNotFoundError(f"缺少 model.onnx: {model_path}")
    inf = onnx_infer.InferenceOnnx(model_path)
    inf.load_config()
    inf.load_model("cpu")
    inf.init_decoder()
    inf.set_progress_callback(log)
    inf.get_dataset(wav_dir, language=language, g2p="dictionary",
                    dictionary_path=dictionary, in_format="lab",
                    insert_sp=bool(p.get("insert_sp", True)))
    inf.infer(non_lexical_phonemes=p.get("non_lexical", "AP,SP"),
              pad_times=int(p.get("pad_times", 1)),
              pad_length=float(p.get("pad_length", 5)),
              merge_phonemes=bool(p.get("merge_phonemes", True)),
              stop_event=_stop)
    inf.export(wav_dir)
    _check_textgrid_prefix(wav_dir)


# ---------------------------------------------------------------------------
# 3. TextGrid → JSON
# ---------------------------------------------------------------------------

def _iter_target_dirs(root: str, recursive: bool):
    root = os.path.abspath(root)
    if not recursive:
        yield root
        for name in sorted(os.listdir(root)):
            sub = os.path.join(root, name)
            if os.path.isdir(sub):
                yield sub
        return
    for cur, _dirs, files in os.walk(root):
        if any(f.endswith(".TextGrid") for f in files):
            yield cur


def _json_phonemes(json_path):
    """收集 ds_phone.json 里出现的音素（排除 SP/AP/R 这类非语言符号）。"""
    try:
        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        return set()
    out = set()
    for item in data.values():
        for ph in (item.get("phones") or {}).values():
            t = str(ph.get("text") or "").strip()
            if t and t.upper() not in ("SP", "AP", "R", "-", "EP"):
                out.add(t)
    return out


def _dict_coverage(dict_path, tokens):
    """某本字典能认下多少比例的 TextGrid 音素；读不了就返回 0。"""
    from tg2oto.textgrid2json import ds_json2filter as filt
    try:
        vowels, consonant = filt.ds_dict_read(dict_path, "")
    except Exception:
        return 0.0
    valid = {x for x in (set(vowels) | set(consonant)) if x}
    if not tokens or not valid:
        return 0.0
    return sum(1 for t in tokens if t in valid) / len(tokens)


def _ensure_json_dictionary(dict_path, json_path, model_dir):
    """JSON 页的字典留空 / 选错时，按 TextGrid 实际音素覆盖率自动挑一本。

    字典一旦和 TextGrid 音素对不上，``ds_json2filter`` 会把所有音素删掉，
    产物里只剩首尾的 R——这类失败是静默的，所以这里先算覆盖率再决定。
    """
    tokens = _json_phonemes(json_path)
    if not tokens:
        return dict_path

    ratio = _dict_coverage(dict_path, tokens) if dict_path else 0.0
    if ratio >= 0.6:
        log(f"[JSON] 字典 {os.path.basename(dict_path)} 覆盖 {ratio:.0%} 的音素")
        return dict_path

    candidates = []
    for base in (model_dir, os.path.join(asset_root(), "dictionary")):
        if base and os.path.isdir(base):
            candidates += [os.path.join(base, n) for n in sorted(os.listdir(base))
                           if n.endswith(".txt")]
    best, best_ratio = dict_path, ratio
    for cand in candidates:
        r = _dict_coverage(cand, tokens)
        if r > best_ratio:
            best, best_ratio = cand, r

    if best and best_ratio > ratio:
        if dict_path:
            log(f"[JSON] ⚠ 原字典只覆盖 {ratio:.0%}，已自动改用 "
                f"{os.path.basename(best)}（覆盖 {best_ratio:.0%}）")
        else:
            log(f"[JSON] 未指定字典，已自动选用 {os.path.basename(best)}（覆盖 {best_ratio:.0%}）")
        return best

    log(f"[JSON] ⚠ 没有任何字典能覆盖这批音素（最高 {best_ratio:.0%}），"
        "过滤阶段会大量删除音素，请检查 JSON 页字典是否与对齐时用的是同一套")
    return best or dict_path


def _job_json(p):
    from tg2oto.textgrid2json import TextGrid2ds_json, ds_json2filter, ds_json2word

    root = p["folder"]
    dict_path = p["dictionary"]
    ignore = p.get("ignore", "")
    rec_preset = (p.get("rec_preset") or "").strip() or None
    model_dir = p.get("model_dir") or ""
    recursive = bool(p.get("recursive", True))

    dirs = [d for d in _iter_target_dirs(root, recursive)
            if any(f.endswith(".TextGrid") for f in os.listdir(d))]
    if not dirs:
        raise FileNotFoundError(f"{root} 下没有找到 .TextGrid 文件")
    log(f"共 {len(dirs)} 个目录待处理")

    for idx, folder in enumerate(dirs, 1):
        _check_cancel()
        log(f"[{idx}/{len(dirs)}] {folder}")
        TextGrid2ds_json.run(folder, rec_preset)

        json_path = os.path.join(folder, "json", "ds_phone.json")
        if not os.path.exists(json_path):
            log(f"⚠ 未生成 ds_phone.json：{folder}")
            continue
        use_dict = _ensure_json_dictionary(dict_path, json_path, model_dir)
        ds_json2filter.run(use_dict, json_path, ignore)

        filter_path = os.path.join(folder, "json", "ds_phone_filter.json")
        if os.path.exists(filter_path):
            ds_json2word.run(use_dict, filter_path)


# ---------------------------------------------------------------------------
# 4. JSON → oto.ini
# ---------------------------------------------------------------------------

def _floats(value, default=None):
    if value is None:
        return list(default or [])
    if isinstance(value, (list, tuple)):
        return [float(v) for v in value]
    return [float(v) for v in str(value).split(",") if str(v).strip() != ""]


def _job_oto(p):
    from tg2oto.json2oto import json2oto, json2CV_oto, json2VCV_oto, json2arpasing_oto, json2phone_oto
    from tg2oto.oto import oto_rw, oto_check

    wav_path = p["wav_dir"]
    presamp = p["presamp"]
    if presamp and not os.path.isabs(presamp):
        presamp = os.path.join(asset_root(), "presamp", presamp)
    mode = str(p.get("mode", "CVVC")).upper()
    # 官方 config/run-config-*.txt 的默认参数，按录制方式兜底，
    # 避免界面上手填残留别的模式的数值。
    _mode_defaults = {
        "CV": ([1, 3, 1, 1, 2], [5, 0, 2, 1, 2], [0, 0, 0, 0, 0]),
        "CVVC": ([1, 3, 1.5, 1, 2], [3, 0, 2, 1, 2], [3, 3, 1.5, 1, 3]),
        "VCV": ([1, 3, 1.5, 1, 2], [3, 3, 1.5, 1, 3, 3], [0, 0, 0, 0, 0]),
        "CVV": ([1, 3, 1, 1, 2], [5, 0, 2, 1, 2], [0, 0, 0, 0, 0]),
        "ARPASING": ([1, 3, 1.5, 1, 2], [3, 0, 2, 1, 2], [3, 3, 1.5, 1, 1.5]),
    }
    _d = _mode_defaults.get(mode, _mode_defaults["CVVC"])
    cv_sum = _floats(p.get("cv_params"), _d[0])
    vc_sum = _floats(p.get("vc_params"), _d[1])
    vv_sum = _floats(p.get("vv_params"), _d[2])
    cv_offset = _floats(p.get("cv_offset"), [0, 0, 0, 0, 0])
    vc_offset = _floats(p.get("vc_offset"), [0, 0, 0, 0, 0])
    cv_repeat = int(float(p.get("cv_repeat", 1)))
    vc_repeat = int(float(p.get("vc_repeat", 1)))
    ignore = p.get("ignore", "")
    template = (p.get("template") or "").strip()
    encoding = p.get("encoding", "UTF-8")
    cover = p.get("cover", "n")
    pitch = p.get("pitch", "")

    word_phone = p.get("word_phone") or os.path.join(wav_path, "json", "word_phone.json")
    if not os.path.isfile(word_phone):
        raise FileNotFoundError(f"缺少 word_phone.json: {word_phone}")

    # cutoff 起算口径开关：UTAU 规范从 offset 起算，OpenUtau 实测从文件末尾倒算。
    _cut_from_end = bool(p.get("cutoff_from_end", False))
    json2VCV_oto.CUTOFF_FROM_END = _cut_from_end
    json2VCV_oto.EXTEND_INITIAL_CUTOFF = bool(p.get("extend_initial_cutoff", False))
    try:
        json2VCV_oto.INITIAL_OFFSET_PREFETCH = max(0.0, float(p.get("initial_offset_prefetch", 0) or 0))
    except Exception:
        json2VCV_oto.INITIAL_OFFSET_PREFETCH = 0.0
    json2CV_oto.CUTOFF_FROM_END = _cut_from_end
    if hasattr(json2oto, "CUTOFF_FROM_END"):
        json2oto.CUTOFF_FROM_END = _cut_from_end
    log(f"录音方案: {mode} | cutoff 起算: {'文件末尾' if _cut_from_end else 'offset'}")
    log(f"presamp: {os.path.basename(presamp)} | json: {word_phone}")
    log(f"忽略音素: {ignore!r} | 左边界/固定/右边界/预发声/交叉 = CV {cv_sum} | VC {vc_sum} | VV {vv_sum}")
    # —— 诊断：把这次 oto 真正读到的文件与解析结果打出来，
    # 便于核对「别名缺失」是数据不同还是代码路径不同。
    log(f"[oto] presamp = {presamp}")
    log(f"[oto] word_phone = {word_phone}")
    log(f"[oto] ignore = {ignore!r} | cv={cv_sum} vc={vc_sum} vv={vv_sum}")
    try:
        _cvv = json2VCV_oto.presamp_read(presamp)
        _miss = [k for k in ("ん", "み", "ぎ", "て", "お", "い") if k not in _cvv]
        log(f"[oto] presamp 解析出 {len(_cvv)} 个音节" +
            (f"，缺: {_miss}" if _miss else "，常用假名齐全"))
    except Exception as e:
        log(f"[oto] presamp 解析失败: {e}")
    try:
        import json as _json
        _d = _json.load(open(word_phone, encoding="utf-8"))
        log(f"[oto] json 文件数 = {len(_d)}")
        for _i, (_k, _v) in enumerate(_d.items()):
            if _i >= 3:
                break
            _ph = sorted(_v.get("phones", {}).values(), key=lambda z: float(z["xmin"]))
            _seq = ",".join(str(x.get("text")) for x in _ph)
            log(f"[oto]   {_k}: {len(_ph)} 个音素 → {_seq}")
    except Exception as e:
        log(f"[oto] json 检查失败: {e}")

    if mode == "VCV":
        json2VCV_oto.run(presamp, word_phone, wav_path, cv_sum, vc_sum, vv_sum, ignore)
    elif mode == "ARPASING":
        json2arpasing_oto.run(presamp, word_phone, wav_path, cv_sum, vc_sum, vv_sum, ignore)
    elif mode == "CVV":
        json2CV_oto.run(presamp, word_phone, wav_path, cv_sum, vc_sum, vv_sum, ignore)
    elif mode == "CVVC":
        json2oto.run(presamp, word_phone, wav_path, cv_sum, vc_sum, vv_sum, ignore)
    elif mode == "PHONE":
        # C+V：逐音素标注，不用 presamp，输出单独的 phone_oto.ini
        json2phone_oto.run(presamp, word_phone, wav_path, cv_sum, vc_sum, vv_sum, ignore)
    else:
        raise ValueError(f"未知录音方案: {mode}")

    # ---- 可选：生成 frq 基频率表（RMVPE）----
    # 逐文件整段推理，CPU 上大约 0.1~0.5 秒/秒音频，Android 上还要慢数倍，
    # 所以这是独立开关，开启前界面会提示「需要较长时间」。
    if bool(p.get("make_frq", False)):
        try:
            from tg2oto.json2oto import frq_gen
            _mp = p.get("rmvpe_model") or ""
            if not _mp or not os.path.isfile(_mp):
                log(f"[frq] 找不到 RMVPE 模型: {_mp}，跳过")
            else:
                log("[frq] 开始用 RMVPE 生成基频率表，这一步比较慢，请耐心等待…")
                def _frq_progress(v):
                    # 直接打到面板进度条；文件级粒度，每处理完一个 wav 前进一格
                    if _sink is not None:
                        try:
                            _sink.progress(min(1.0, max(0.0, float(v))))
                        except Exception:
                            pass

                frq_gen.generate_for_dir(
                    wav_path, _mp,
                    threads=int(p.get("threads") or 2),
                    log=log,
                    should_stop=is_cancelled,
                    progress=_frq_progress,
                )
        except Exception as e:
            log(f"[frq] 生成失败: {e}")

    try:
        import json as _json
        _d = _json.load(open(word_phone, 'r', encoding='utf-8'))
        log(f"json 里共 {len(_d)} 个音频、{sum(len(v.get('phones', {})) for v in _d.values())} 个音素")
        for _k in list(_d)[:3]:
            _ph = sorted(_d[_k].get('phones', {}).items(), key=lambda x: int(x[0]))
            _txt = " / ".join(str(v.get('text')) for _, v in _ph)
            log(f"  {_k}: {len(_ph)} 个音素 → {_txt}")
    except Exception as _e:
        log(f"读取 json 失败: {_e}")

    # ---- presamp 基准自检 ----
    # 不看生成器输出，直接从 json 音素 + presamp 表独立推导「应有别名」，
    # 再与 oto 里实际存在的别名做差集，因此生成器自己漏掉的条目也能被发现。
    #   VCV: 首音节 → "- X"；相邻 (A,B) → "CV_V[A] B"；末音节接 R → "CV_V[A] R"
    try:
        import json as _json4
        _d4 = _json4.load(open(word_phone, 'r', encoding='utf-8'))
        _B = ("R", "SP", "AP", "-", "sil")
        _CV = json2VCV_oto.presamp_read(presamp)
        _CV['R'] = '-'
        _act = {}
        for _f in [x for x in os.listdir(wav_path) if x.endswith("_oto.ini") or x == "oto.ini"]:
            for _l in open(os.path.join(wav_path, _f), 'r', encoding='utf-8'):
                if "=" not in _l:
                    continue
                _w, _r = _l.split("=", 1)
                _a = _r.split(",")[0].strip()
                if not _a:
                    continue
                _w = _w.strip()
                if _w.lower().endswith(".wav"):
                    _w = _w[:-4]
                _act.setdefault(_w, set()).add(_a)
        _miss_files = []
        _total_miss = 0
        for _k, _v in _d4.items():
            _key = _k[:-4] if _k.lower().endswith(".wav") else _k
            _ph = sorted(_v.get("phones", {}).items(), key=lambda x: int(x[0]))
            _t = [str(p.get("text")) for _, p in _ph]
            _real = [x for x in _t if x not in _B]
            if not _real:
                continue
            _exp = {"- " + _real[0]}
            for _i in range(len(_real) - 1):
                _a, _b = _real[_i], _real[_i + 1]
                if _a in _CV:
                    _exp.add(_CV[_a] + " " + _b)
            if _real[-1] in _CV:
                _exp.add(_CV[_real[-1]] + " R")
            _got = _act.get(_key, set())
            _lack = sorted(_exp - _got)
            if _lack:
                _total_miss += len(_lack)
                _miss_files.append((_key, _lack))
        log(f"presamp 基准自检: {len(_miss_files)}/{len(_d4)} 个文件缺别名，共缺 {_total_miss} 条")
        for _k, _l in _miss_files[:15]:
            log(f"  {_k}: 缺 {len(_l)} 条 → {' / '.join(_l)}")
        _by_sym = {}
        for _k, _l in _miss_files:
            for _a in _l:
                _sym = _a.split()[-1]
                _by_sym[_sym] = _by_sym.get(_sym, 0) + 1
        if _by_sym:
            _top = sorted(_by_sym.items(), key=lambda x: -x[1])[:20]
            log("缺失频次最高的音节: " + " | ".join(f"{k}×{v}" for k, v in _top))
    except Exception as _e:
        log(f"presamp 基准自检失败: {_e}")

    _check_cancel()
    cv = oto_rw.oto_read(os.path.join(wav_path, "cv_oto.ini"))
    vc = oto_rw.oto_read(os.path.join(wav_path, "vc_oto.ini"))
    if not template or not os.path.exists(template):
        cv = oto_rw.oto_repeat(cv, cv_repeat)
        vc = oto_rw.oto_repeat(vc, vc_repeat)
    if cv_offset != [0.0, 0.0, 0.0, 0.0, 0.0]:
        cv = oto_rw.oto_offset(cv, cv_offset)
    if vc_offset != [0.0, 0.0, 0.0, 0.0, 0.0]:
        vc = oto_rw.oto_offset(vc, vc_offset)

    oto_rw.oto_write(os.path.join(wav_path, "auto_oto.ini"), cv + vc, "", "y", encoding)
    oto_data = oto_rw.oto_read(os.path.join(wav_path, "auto_oto.ini"))
    if template and os.path.exists(template):
        log(f"应用模板: {template}")
        oto_data = oto_rw.oto_apply_template(oto_data, template, pitch)
    written = oto_rw.oto_write(os.path.join(wav_path, "oto.ini"), oto_data, pitch, cover, encoding)
    if not written:
        written = os.path.join(wav_path, "oto.ini")
    oto_check.run(written, presamp, pitch, p.get("vcv_mode", mode))


# ---------------------------------------------------------------------------
# 5. 工具
# ---------------------------------------------------------------------------

def _job_phnum(p):
    from tg2oto.build_diffdataset import add_ph_num

    add_ph_num.run(
        p["csv"],
        dictionary=p.get("dictionary") or None,
        vowels=p.get("vowels") or None,
        consonants=p.get("consonants") or None,
        progress_callback=log,
    )


def _job_csv2tg(p):
    from tg2oto.build_diffdataset import csv2tg

    csv2tg.run(p["csv"], out_dir=p.get("out_dir") or None,
               dictionary=p.get("dictionary") or None, progress_callback=log)


def _job_words2lab(p):
    from tg2oto.build_diffdataset import lab_from_words

    lab_from_words.run(p["tg_dir"], out_dir=p.get("out_dir") or None,
                       ignore=tuple(x for x in (p.get("ignore") or "").split(",") if x),
                       recursive=bool(p.get("recursive", True)),
                       progress_callback=log)


def _job_loudness(p):
    from tg2oto.build_diffdataset import check_loudness

    check_loudness.run(p["wav_dir"], threshold_db=float(p.get("threshold_db", -40)),
                       peak_threshold_db=float(p.get("peak_threshold_db", -30)))


def _job_delsp(p):
    from tg2oto.textgrid2json import del_SP

    del_SP.process_all_textgrid_files(p["folder"], p.get("phones", "AP,SP,EP"), True)


def _job_build_dataset(p):
    from tg2oto.build_diffdataset import build_dataset

    build_dataset.build_dataset(
        p["wavs_dir"], p.get("tg_dir") or None, p["dataset_dir"],
        progress_callback=log,
        recursive=bool(p.get("recursive", False)),
        convert_audio=bool(p.get("convert_audio", True)),
    )


def _job_game(p):
    from tg2oto.inference import game_infer

    lang = p.get("language") or None
    if lang in ("auto", "", "Unset"):
        lang = None
    csv_path = p["csv"]
    model_dir = p["model_dir"]
    log(f"GAME 模型目录：{model_dir}")
    stats = game_infer.align_transcriptions(
        csv_path,
        model_dir,
        language=lang,
        device=p.get("device", "cpu"),
        output_path=p.get("output") or None,
        overwrite=bool(p.get("overwrite", True)),
        use_wb=bool(p.get("use_wb", True)),
        uv_vocab=p.get("uv_vocab") or None,
        uv_word_cond=p.get("uv_word_cond", "lead"),
        uv_note_cond=p.get("uv_note_cond", "predict"),
        seg_threshold=float(p.get("seg_threshold", 0.2)),
        seg_radius=float(p.get("seg_radius", 0.02)),
        t0=float(p.get("t0", 0.0)),
        nsteps=int(p.get("nsteps", 8)),
        est_threshold=float(p.get("est_threshold", 0.2)),
        max_chunk_sec=float(p.get("max_chunk_sec", 30.0)),
        workers=int(p.get("workers", 1)),
        progress_callback=log,
        stop_event=_stop,
    )
    log(f"GAME 标注完成：{stats}")


_HANDLERS = {
    "lab": _job_lab,
    "align": _job_align,
    "json": _job_json,
    "oto": _job_oto,
    "phnum": _job_phnum,
    "csv2tg": _job_csv2tg,
    "words2lab": _job_words2lab,
    "loudness": _job_loudness,
    "delsp": _job_delsp,
    "dataset": _job_build_dataset,
    "game": _job_game,
}


# ---------------------------------------------------------------------------
# 资源路径（Chaquopy 会把包内数据文件提取到文件系统）
# ---------------------------------------------------------------------------

def resources() -> str:
    """内置字典 / presamp 清单，供界面下拉。"""
    root = asset_root()
    presamp = []
    for dirpath, _dirs, files in os.walk(os.path.join(root, "presamp")):
        for f in files:
            if f.endswith(".ini"):
                presamp.append(os.path.relpath(os.path.join(dirpath, f), os.path.join(root, "presamp")))
    dicts = []
    for dirpath, _dirs, files in os.walk(os.path.join(root, "dictionary")):
        for f in files:
            if f.endswith(".txt"):
                dicts.append(os.path.relpath(os.path.join(dirpath, f), os.path.join(root, "dictionary")))
    return json.dumps({
        "presamp": sorted(presamp),
        "dicts": sorted(dicts),
        "presamp_root": os.path.join(root, "presamp"),
        "dict_root": os.path.join(root, "dictionary"),
    }, ensure_ascii=False)


def asset_root() -> str:
    return os.path.dirname(os.path.abspath(__file__))


def presamp_path(name: str) -> str:
    root = asset_root()
    mapping = {
        "ja-presamp.ini": os.path.join(root, "presamp", "ja-presamp.ini"),
        "en-arpasing-presamp.ini": os.path.join(root, "presamp", "en-arpasing-presamp.ini"),
        "中文CV-presamp.ini": os.path.join(root, "presamp", "中文CV-presamp.ini"),
        "risku式中文CVVCpresamp.ini": os.path.join(root, "presamp", "risku式中文CVVCpresamp.ini"),
        "中文VCV-presamp.ini": os.path.join(root, "presamp", "presamp", "中文VCV-presamp.ini"),
    }
    return mapping.get(name, os.path.join(root, "presamp", name))
