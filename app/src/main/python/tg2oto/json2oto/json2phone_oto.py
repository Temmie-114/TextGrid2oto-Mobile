import json

# C+V 逐音素标注：不依赖 presamp 表，也不做任何音节映射，
# 直接把对齐结果里每个音素写成一条 oto 条目，别名就是该音素本身。
# 正因为它只认「音素文本」，日文假名 / 中文 X-SAMPA / 英文 ARPAbet 走的是同一条路径，
# 所以可以跨语种使用。
#
# 输出文件是 phone_oto.ini，不与 cv_oto.ini / vc_oto.ini 混写，
# 避免和现有四个模式的 presamp 体系互相覆盖。
#
# 五条线按「毫秒偏移」放，不按中点比例：对齐器只给音素边界，
# 并不知道哪一段稳定，所以用相对起点/终点的固定毫秒来表达，用户能直接调。

# 元音集合（小写）。只用来决定这一条按「C 参数」还是「V 参数」计算，
# 覆盖日语元音名、中文 X-SAMPA 韵母、英文 ARPAbet 元音。
_VOWELS = set("""
a i u e o n
aa ae ah ao aw ax ay eh er ey ih iy oh ow oy uh uw
ai aI ao au ei ou ey oy e0 en0 i0 ir v0 vn an ang en eng in ing ong uo
""".split())

# 日语假名的元音音节（あ行）与 ん：不补进来会被当成辅音，套上 C 那套「短预发声」参数。
_VOWELS |= set("あいうえおアイウエオんン")

_BND = ("R", "SP", "AP", "sil", "-")


def _alias(text):
    # oto.ini 按「别名,左边界,预发声,重叠,固定,右边界」逐字段解析，
    # 别名里出现逗号或等号会把整行读坏；空格会被当成两段别名的分隔符。
    # X-SAMPA 的反斜杠、下划线是音素本身的一部分，保留不动。
    s = str(text).strip()
    for ch in (",", "=", "\r", "\n"):
        s = s.replace(ch, "_")
    return s.replace(" ", "_")


def _params(xmin, xmax, total_ms, sm):
    """五条线按毫秒放。

    sm 依次是：前留(ms) / 预发声偏移(ms) / 重叠比 / 固定长度(ms) / 尾巴(ms)

      offset    = 音素起点前移「前留」，给上一条留尾巴
      pre       = 音素起点 + 预发声偏移，跳过起音不稳段
      overlap   = pre × 重叠比（辅音取大、元音取小）
      consonant = 固定长度，辅音用它撑住主体，元音给 0
      cutoff    = -(音频总长 − 音素终点 − 尾巴)，即从右端裁到本音素之后一点；
                  拿不到总长时退回 -(尾巴)

    返回顺序按 UTAU 标准：offset, preutterance, overlap, consonant, cutoff。
    """
    start = float(xmin) * 1000.0   # json 里是秒，oto 里是毫秒
    end = float(xmax) * 1000.0
    lead, pre_off, ov_ratio, cons_len, tail = [float(x) for x in sm[:5]]

    offset = max(0.0, start - lead)
    pre = max(1.0, (start + pre_off) - offset)
    overlap = pre * ov_ratio
    consonant = cons_len
    if total_ms > 0:
        cutoff = -max(1.0, total_ms - (end + tail))
    else:
        cutoff = -abs(tail)
    return offset, pre, overlap, consonant, cutoff


def run(presamp_path, word_phone_json, wav_path, cv_sum, vc_sum, vv_sum, ignore):
    """presamp_path 不使用，保留是为了与其他生成器共用同一条调用签名。"""
    with open(word_phone_json, "r", encoding="utf-8") as f:
        data = json.load(f)
    ign = set(str(x).strip() for x in str(ignore or "").split(",") if str(x).strip())

    lines = []
    for audio, info in data.items():
        phones = info.get("phones", {})
        if not phones:
            continue
        # long[1] 是该条音频的总长（秒），用于把右边界裁在本音素之后
        long = info.get("long") or info.get("wav_long") or []
        total_ms = float(long[1]) * 1000.0 if len(long) >= 2 else 0.0

        seen = set()  # 同一音频里同名音素只留第一条，后面会互相覆盖
        for _, ph in sorted(phones.items(), key=lambda kv: int(kv[0])):
            text = str(ph.get("text", "")).strip()
            if not text or text in _BND or text in ign:
                continue
            a = _alias(text)
            if not a or a in seen:
                continue
            seen.add(a)
            sm = vv_sum if text.lower() in _VOWELS else cv_sum
            offset, pre, overlap, consonant, cutoff = _params(
                ph["xmin"], ph["xmax"], total_ms, sm)
            _wav = audio if str(audio).lower().endswith(".wav") else f"{audio}.wav"
            # 上游内部顺序是 offset, fixed, -right, Prevoice, cross，
            # 与 UTAU 文档里的写法不同；后面的 oto_read / oto_check 都按这套解析，
            # 所以这里必须按同一顺序落盘，否则 overlap 会被当成右边界换算成负数。
            lines.append(f"{_wav}={a},{offset},{consonant},{cutoff},{pre},{overlap}\n")

    with open(wav_path + "/phone_oto.ini", "w", encoding="utf-8") as f:
        for l in lines:
            f.write(l)
    print(f"phone_oto.ini 生成成功：{len(lines)} 条")
    return len(lines)
