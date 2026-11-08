"""RMVPE 音高提取（纯 NumPy 前端 + ONNX Runtime）。

移植自 rmvpe-onnx 0.2.3（MIT，liujing04 / 源文雨 / Ftps）与 RVC 的 rmvpe.py（MIT），
模型权重来自 lj1995/VoiceConversionWebUI（MIT）。

不使用 librosa / scipy：mel 滤波器组、Hann 窗、STFT、降采样全部用 NumPy 现算，
原因与 game_infer 去掉 librosa 相同——Chaquopy 里带不动那套依赖。

输出：每 10ms 一帧的 f0（Hz）与发声置信度，供 frq_gen 写成 .frq。
"""

import numpy as np

try:
    import onnxruntime as ort
except Exception:  # 让上层能捕获并给出「模型/运行时缺失」的提示
    ort = None

SR = 16000
NFFT = 1024
HOP = 160
N_MELS = 128
FMIN = 30.0
FMAX = 8000.0
N_CLASS = 360
CENTS_OFFSET = 1997.3794084376191
UV_TH = 0.03          # 置信度低于此值判为无声
FRAME_MS = HOP / SR * 1000.0   # 10ms


def _hz2mel(f):
    return 2595.0 * np.log10(1.0 + np.asarray(f, dtype=np.float64) / 700.0)


def _mel2hz(m):
    return 700.0 * (10.0 ** (np.asarray(m, dtype=np.float64) / 2595.0) - 1.0)


def _mel_basis():
    """HTK mel + slaney 归一化，与 librosa.filters.mel(htk=True) 一致。"""
    fftfreqs = np.linspace(0.0, SR / 2.0, NFFT // 2 + 1)
    mel_f = np.linspace(_hz2mel(FMIN), _hz2mel(FMAX), N_MELS + 2)
    hz = _mel2hz(mel_f)
    fdiff = np.diff(hz)
    ramps = hz[:, None] - fftfreqs[None, :]
    lower = -ramps[:-2] / fdiff[:-1, None]
    upper = ramps[2:] / fdiff[1:, None]
    w = np.maximum(0.0, np.minimum(lower, upper))
    w *= (2.0 / (hz[2:] - hz[:-2]))[:, None]  # slaney norm
    return w.astype(np.float32)


_MEL = _mel_basis()
_WIN = (0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(NFFT) / NFFT)).astype(np.float32)


def _lowpass(x, cutoff_hz):
    """窗函数 sinc 低通，降采样前抗混叠。"""
    taps = 63
    n = np.arange(taps) - taps // 2
    fc = min(0.49, cutoff_hz / SR)
    h = 2.0 * fc * np.sinc(2.0 * fc * n) * np.hamming(taps)
    s = h.sum()
    if s != 0:
        h /= s
    return np.convolve(x, h.astype(np.float64), mode="same")


def resample(x, sr_in, sr_out=SR):
    """降采样到 16k：先低通再线性插值。升采样直接线性插值。"""
    x = np.asarray(x, dtype=np.float64)
    if sr_in == sr_out or x.size == 0:
        return x.astype(np.float32)
    if sr_out < sr_in:
        x = _lowpass(x, sr_out / 2.0 * 0.9)
    n_out = int(round(x.size * sr_out / float(sr_in)))
    if n_out <= 1:
        return x[:1].astype(np.float32)
    t = np.arange(n_out) * (sr_in / float(sr_out))
    return np.interp(t, np.arange(x.size), x).astype(np.float32)


def logmel(audio16k):
    """(128, T) 的 log-mel，帧移 160（10ms），与 RMVPE 训练时一致。"""
    a = np.asarray(audio16k, dtype=np.float32)
    a = np.pad(a, NFFT // 2, mode="reflect")
    n = max(1, (a.size - NFFT) // HOP + 1)
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(n)[:, None]
    frames = a[idx] * _WIN
    mag = np.abs(np.fft.rfft(frames, axis=1)).T.astype(np.float32)
    mel = _MEL @ mag
    return np.log(np.clip(mel, 1e-5, None)).astype(np.float32)


class RMVPE:
    def __init__(self, model_path, threads=2):
        if ort is None:
            raise RuntimeError("onnxruntime 不可用")
        so = ort.SessionOptions()
        so.intra_op_num_threads = max(1, int(threads))
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.sess = ort.InferenceSession(
            model_path, so, providers=["CPUExecutionProvider"])
        self.inp = self.sess.get_inputs()[0].name
        self.out = self.sess.get_outputs()[0].name
        self.cents_map = np.pad(20.0 * np.arange(N_CLASS) + CENTS_OFFSET, (4, 4))

    def f0(self, audio16k):
        """返回 (f0_hz, confidence)，每帧 10ms。"""
        mel = logmel(audio16k)[None, :, :]
        n = mel.shape[-1]
        pad = 32 * ((n - 1) // 32 + 1) - n
        if pad:
            mel = np.pad(mel, ((0, 0), (0, 0), (0, pad)))
        act = self.sess.run([self.out], {self.inp: mel})[0][0][:n].astype(np.float32)
        conf = act.max(axis=1)

        center = np.argmax(act, axis=1)
        sal = np.pad(act, ((0, 0), (4, 4)))
        center = center + 4
        fi = np.arange(sal.shape[0])[:, None]
        off = np.arange(9)[None, :]
        idx = (center[:, None] - 4) + off
        ws = sal[fi, idx]
        wc = self.cents_map[idx]
        denom = ws.sum(1)
        denom[denom == 0] = 1e-8
        cents = (ws * wc).sum(1) / denom

        f0 = 10.0 * (2.0 ** (cents / 1200.0))
        f0[conf < UV_TH] = 0.0
        return f0.astype(np.float32), conf.astype(np.float32)
