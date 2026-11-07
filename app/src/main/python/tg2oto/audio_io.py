# -*- coding: utf-8 -*-
"""音频读取与重采样，替代 librosa / soundfile 的最小实现。

Android 端不引入 librosa（依赖 numba/scipy，体积与构建风险都高），
这里用标准库 wave + numpy 实现等价行为：

* ``load(path, sr, mono)`` —— 与 ``librosa.load`` 相同语义：PCM 归一化到
  float32 [-1, 1)，多声道取均值，按需重采样。
* ``resample(x, orig_sr, target_sr)`` —— 有理数多相（polyphase）重采样，
  与 ``scipy.signal.resample_poly`` 同一套窗函数法设计，质量足以喂给
  强制对齐模型。
"""

from __future__ import annotations

import math
import os
import wave

import numpy as np


# ---------------------------------------------------------------------------
# 读取
# ---------------------------------------------------------------------------

_SUPPORTED_SUBTYPES = {"PCM_16": 16, "PCM_24": 24, "PCM_32": 32, "PCM_U8": 8, "PCM_FLOAT": 32}


class AudioLoadError(RuntimeError):
    pass


def _read_pcm_wav(path: str):
    with wave.open(path, "rb") as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        framerate = wf.getframerate()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)

    if sampwidth == 1:  # u8
        data = np.frombuffer(raw, dtype=np.uint8).astype(np.float32)
        data = (data - 128.0) / 128.0
    elif sampwidth == 2:  # i16
        data = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif sampwidth == 3:  # i24
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        v = np.where(v >= 0x800000, v - 0x1000000, v)
        data = v.astype(np.float32) / 8388608.0
    elif sampwidth == 4:  # i32
        data = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise AudioLoadError(f"不支持的采样位深: {sampwidth * 8} bit")

    if n_channels > 1:
        data = data.reshape(-1, n_channels)
    else:
        data = data.reshape(-1, 1)
    return data, framerate


def _write_float_wav(path: str, data: np.ndarray, sr: int, channels: int = 1):
    """把 float32 [-1,1] 写成 16bit PCM（用于「量化到 16bit」的输出）。"""
    pcm = np.clip(data, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(path, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(2)
        wf.setframerate(int(sr))
        wf.writeframes(pcm.tobytes())


def read_audio(path: str) -> tuple[np.ndarray, int]:
    """返回 (samples[n,1] float32, sample_rate)。"""
    if not os.path.isfile(path):
        raise AudioLoadError(f"文件不存在: {path}")
    ext = os.path.splitext(path)[1].lower()
    if ext in (".wav", ".wave"):
        try:
            return _read_pcm_wav(path)
        except wave.Error as exc:
            raise AudioLoadError(f"无法解析 wav: {path} ({exc})") from exc
    raise AudioLoadError(
        f"暂不支持 {ext} 格式，请使用 16bit PCM 的 .wav（UTAU 音源通常都是这种）"
    )


def load(path: str, sr: int | None = None, mono: bool = True):
    """等价于 ``librosa.load``。"""
    data, orig_sr = read_audio(str(path))
    if mono and data.shape[1] > 1:
        data = data.mean(axis=1, keepdims=True)
    y = data[:, 0] if mono else data.T
    if sr is not None and sr != orig_sr:
        if mono:
            y = resample(np.asarray(y, dtype=np.float32), orig_sr=orig_sr, target_sr=int(sr))
        else:
            y = np.stack(
                [resample(np.asarray(ch, dtype=np.float32), orig_sr=orig_sr, target_sr=int(sr))
                 for ch in y],
                axis=0,
            )
    return np.asarray(y, dtype=np.float32), int(sr or orig_sr)


def write(path: str, y: np.ndarray, sr: int):
    """把 float32 波形写成 16bit PCM wav。"""
    y = np.asarray(y, dtype=np.float32)
    if y.ndim == 1:
        y = y.reshape(-1, 1)
    _write_float_wav(path, y, sr, channels=y.shape[1])


# ---------------------------------------------------------------------------
# 重采样（有理数多相）
# ---------------------------------------------------------------------------

_KAISER_BETA = 5.0
_HALF_LEN = 10  # 与 scipy.resample_poly 默认一致：half_len = 10 * max(up, down)


def _design_filter(up: int, down: int) -> np.ndarray:
    """Kaiser 窗 sinc 低通（采样率 = up 倍），截止 0.5/max(up, down)。"""
    half = _HALF_LEN * max(up, down)
    n = np.arange(-half, half + 1, dtype=np.float64)
    cutoff = 0.5 / max(up, down)
    h = np.zeros_like(n)
    central = n == 0
    h[central] = 2 * cutoff
    h[~central] = np.sin(2 * np.pi * cutoff * n[~central]) / (np.pi * n[~central])
    h *= np.kaiser(h.shape[0], _KAISER_BETA)
    h *= up  # 插值增益
    return h.astype(np.float64)


def _polyphase(h: np.ndarray, up: int) -> np.ndarray:
    """把 h 拆成 up 个相位，返回 [phase, taps]（第 p 相 = h[p::up]）。"""
    total = int(np.ceil(h.shape[0] / up) * up)
    hp = np.zeros(total, dtype=np.float64)
    hp[: h.shape[0]] = h
    return hp.reshape(-1, up).T


def resample(y: np.ndarray, orig_sr: int, target_sr: int,
             res_type: str | None = None, axis: int = -1) -> np.ndarray:
    """有理数多相重采样；``res_type`` 仅为兼容 librosa 签名而保留。

    等价于 scipy 的 ``upfirdn(h, x, up, down)``：
        y[i] = sum_t h[p + t*up] * x[i*down//up - t],  p = (i*down) % up
    """
    if orig_sr == target_sr:
        return np.asarray(y, dtype=np.float32)
    g = math.gcd(int(orig_sr), int(target_sr))
    up = int(target_sr) // g
    down = int(orig_sr) // g

    x = np.asarray(y, dtype=np.float64).ravel()
    h = _design_filter(up, down)

    # —— 与 scipy.signal.resample_poly 相同的零点补偿 ——
    half = _HALF_LEN * max(up, down)
    n_pre_pad = down - (half % down)
    n_pre_remove = (half + n_pre_pad) // down
    if n_pre_pad:
        h = np.concatenate((np.zeros(n_pre_pad, dtype=np.float64), h))
    phases = _polyphase(h, up)
    taps = phases.shape[1]

    n_in = x.shape[0]
    n_out = int(math.ceil(n_in * up / down))
    n_calc = n_out + n_pre_remove

    padded = np.zeros(n_in + 2 * taps, dtype=np.float64)
    padded[taps: taps + n_in] = x

    rows = np.arange(taps)
    block = 2048
    y_raw = np.empty(n_calc, dtype=np.float64)
    for s in range(0, n_calc, block):
        e = min(s + block, n_calc)
        i = np.arange(s, e, dtype=np.int64)
        pos = i * down
        base_m = pos // up
        ker = phases[pos % up, :]
        idx = (taps + base_m)[:, None] - rows[None, :]
        y_raw[s:e] = np.sum(padded[idx] * ker, axis=1)

    y_out = y_raw[n_pre_remove: n_pre_remove + n_out]
    return np.asarray(y_out, dtype=np.float32)


# ---------------------------------------------------------------------------
# librosa 兼容的小工具
# ---------------------------------------------------------------------------

def midi_to_note(midi: float, cents: bool = False, unicode: bool = False,
                 octave: bool = True, key: str | None = None) -> str:
    """等价于 ``librosa.midi_to_note`` 的常用子集。"""
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    m = float(midi)
    if cents:
        deg = int(np.floor(m))
        frac = m - deg
        name = names[deg % 12] + str(deg // 12 - 1)
        if abs(frac) > 1e-9:
            name += f"{frac * 100:+.0f}"
        return name
    deg = int(round(m))
    return names[deg % 12] + str(deg // 12 - 1)
