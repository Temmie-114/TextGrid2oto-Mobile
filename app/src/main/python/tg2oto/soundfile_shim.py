# -*- coding: utf-8 -*-
"""soundfile 的轻量替身（只覆盖项目实际用到的 read/write）。"""

from __future__ import annotations

import numpy as np

from tg2oto import audio_io


def read(path, dtype="float32", always_2d=False, frames=None, start=None, **kwargs):
    data, sr = audio_io.read_audio(str(path))
    if frames is not None:
        data = data[start or 0: (start or 0) + frames]
    if not always_2d and data.shape[1] == 1:
        data = data[:, 0]
    if dtype == "float32":
        data = data.astype(np.float32)
    return data, sr


def write(path, data, samplerate=None, channels=None, subtype="PCM_16", **kwargs):
    if samplerate is None:
        raise ValueError("samplerate 不能为空")
    audio_io.write(str(path), np.asarray(data, dtype=np.float32), int(samplerate))


def info(path):
    data, sr = audio_io.read_audio(str(path))

    class _Info:
        samplerate = sr
        channels = data.shape[1]
        frames = data.shape[0]

    return _Info()
