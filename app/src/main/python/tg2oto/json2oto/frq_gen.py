import os
import numpy as np

# UTAU / OpenUtau 的频率表（.frq）二进制格式：
#   "FREQ0003"                     9 字节魔数
#   uint32 LE   hop                每帧对应的采样点数，UTAU 固定 256
#   float64 LE  平均 f0            读方只用来做基准
#   16 字节     生成者签名          任意填充，读取时忽略
#   uint32 LE   帧数
#   帧数据      [f0 float64, 音量 float64] × 帧数，交错存放
# 帧距 = hop / 采样率，44100Hz 下约 5.8ms。
#
# 注：OpenUtau 也能直接读这种格式，所以在 App 里生成后不需要再跑一遍 resampler。

HOP = 256
SIG = b"PyRwu           "  # 16 字节，与社区实现一致


def frame_count(n_samples, hop=HOP):
    return max(1, int(n_samples) // hop)


def write_frq(path, f0, amp, hop=HOP):
    """f0 / amp 必须是等长的逐帧数组（帧距 hop 个采样点）。"""
    f0 = np.asarray(f0, dtype=np.float64)
    amp = np.asarray(amp, dtype=np.float64)
    if f0.shape[0] != amp.shape[0]:
        raise ValueError("f0 与音量帧数不一致")
    avg = float(np.average(f0)) if f0.size else 0.0
    with open(path, "wb") as fw:
        fw.write(b"FREQ0003")
        fw.write(int(hop).to_bytes(4, "little"))
        fw.write(np.array(avg, dtype=np.float64).tobytes())
        fw.write(SIG)
        fw.write(int(f0.shape[0]).to_bytes(4, "little"))
        fw.write(np.concatenate([[f0], [amp]]).T.astype(np.float64).tobytes())


def resample_to_hop(f0, src_period_ms, n_samples, hop=HOP, sr=44100):
    """把等间隔的 f0 序列插值到 .frq 的帧网格上。

    src_period_ms 是原序列每帧的毫秒数（RMVPE 一般 10ms）。
    末尾多出来的帧沿用最后一个值，静音段（f0=0）原样保留成 0。
    """
    n = frame_count(n_samples, hop)
    if n <= 0:
        return np.zeros(1), np.zeros(1)
    # 目标帧中心时间（毫秒）
    t_dst = (np.arange(n) + 0.5) * (hop / float(sr)) * 1000.0
    t_src = (np.arange(len(f0)) + 0.5) * float(src_period_ms)
    if len(f0) == 0:
        return np.zeros(n), np.zeros(n)
    val = np.interp(t_dst, t_src, np.asarray(f0, dtype=np.float64))
    # 插值会把 f0=0 的静音段拖出非零值；凡两端都是 0 的区间强制归零
    zero = (np.asarray(f0) <= 0)
    if zero.any():
        zv = np.interp(t_dst, t_src, zero.astype(np.float64))
        val[zv > 0.99] = 0.0
    return val, np.zeros(n)


def _read_wav(path):
    """返回 (单声道 float32 样本, 采样率)。用标准库 wave，避免引入额外依赖。"""
    import wave
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if sw == 2:
        x = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif sw == 1:
        x = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif sw == 4:
        x = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648.0
    elif sw == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        v = (b[:, 2] << 16) | (b[:, 1] << 8) | b[:, 0]
        v = np.where(v >= 0x800000, v - 0x1000000, v)
        x = v.astype(np.float32) / 8388608.0
    else:
        raise ValueError(f"不支持的位深: {sw * 8} bit")
    if nch > 1:
        x = x.reshape(-1, nch).mean(axis=1)
    return x, sr


def generate_for_dir(wav_dir, model_path, threads=2, log=print, should_stop=None, progress=None):
    """对目录下每个 .wav 生成同名 .frq。返回 (成功数, 失败数)。

    RMVPE 是逐文件整段推理，CPU 上大约 0.1~0.5 秒/秒音频，
    Android 上会慢数倍，所以调用方必须在界面上提示「需要较长时间」。
    """
    from tg2oto.inference.rmvpe_infer import RMVPE, resample, FRAME_MS

    # 递归扫描：录音目录常按角色/批次分子目录，扩展名大小写也不统一
    files = []
    for _root, _dirs, _names in os.walk(wav_dir):
        for _n in _names:
            if _n.lower().endswith(".wav"):
                files.append(os.path.join(_root, _n))
    files.sort()
    log(f"[frq] 目录 {wav_dir} 下找到 {len(files)} 个 wav")
    if not files:
        try:
            _sample = sorted(os.listdir(wav_dir))[:6]
        except Exception:
            _sample = []
        log(f"[frq] 没有 wav，目录内容示例：{_sample}")
        return 0, 0
    model = RMVPE(model_path, threads=threads)
    ok = fail = 0

    def _emit(cur, total):
        # 进度条：既走回调，也写一行 [i/n] 让日志解析器认得
        if progress is not None:
            try:
                progress(min(1.0, cur / float(total)))
            except Exception:
                pass
    for i, f in enumerate(files, 1):
        if should_stop is not None and should_stop():
            log("[frq] 已取消")
            break
        try:
            x, sr = _read_wav(f)
            if x.size == 0:
                fail += 1
                continue
            f0, _conf = model.f0(resample(x, sr))
            n = frame_count(x.size, HOP)
            f0r, _ = resample_to_hop(f0, FRAME_MS, x.size, hop=HOP, sr=sr)
            # 音量：与 PyRwu 一致，按每帧 hop 个采样点取平均绝对值
            amp = np.zeros(n, dtype=np.float64)
            for k in range(n):
                seg = x[k * HOP:(k + 1) * HOP]
                amp[k] = float(np.average(np.abs(seg))) if seg.size else 0.0
            out = os.path.splitext(f)[0] + ".frq"
            write_frq(out, f0r[:n], amp, hop=HOP)
            ok += 1
            _emit(i, len(files))
            log(f"[frq] [{i}/{len(files)}] {os.path.basename(f)} → {os.path.basename(out)}")
        except Exception as e:
            fail += 1
            _emit(i, len(files))
            log(f"[frq] [{i}/{len(files)}] {os.path.basename(f)} 失败: {e}")
    log(f"[frq] 完成：成功 {ok}，失败 {fail}")
    return ok, fail

