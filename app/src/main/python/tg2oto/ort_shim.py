# -*- coding: utf-8 -*-
"""onnxruntime 的 Android 替身。

Android 上 Python 侧装不了 onnxruntime（没有对应 wheel），真正的推理由
Kotlin 侧的 ``com.tg2oto.mobile.core.OrtEngine`` 执行。本模块实现
``onnxruntime`` 的最小可用子集（InferenceSession / SessionOptions /
get_available_providers ...），让原项目的推理代码只需替换 import 即可运行。

张量通过「二进制文件 + manifest」跨语言传递，避免 JNI 上的大数组拷贝与
堆内存抖动；session 在 Kotlin 端按模型路径缓存，一个批次只加载一次权重。
"""

from __future__ import annotations

import json
import os
import shutil
import tempfile

import numpy as np

from java import jclass

_OrtEngine = None


def _engine():
    global _OrtEngine
    if _OrtEngine is None:
        _OrtEngine = jclass("com.tg2oto.mobile.core.OrtEngine")
    return _OrtEngine


# ---------------------------------------------------------------------------
# 与 onnxruntime 对齐的常量 / 类
# ---------------------------------------------------------------------------

class GraphOptimizationLevel:
    ORT_DISABLE_ALL = 0
    ORT_ENABLE_BASIC = 1
    ORT_ENABLE_EXT = 2
    ORT_ENABLE_ALL = 3


class ExecutionMode:
    ORT_SEQUENTIAL = 0
    ORT_PARALLEL = 1


class SessionOptions:
    """只保留原代码会赋值的字段，其余忽略。"""

    def __init__(self):
        self.graph_optimization_level = GraphOptimizationLevel.ORT_ENABLE_ALL
        self.enable_mem_pattern = True
        self.enable_cpu_mem_arena = True
        self.execution_mode = ExecutionMode.ORT_SEQUENTIAL
        self.inter_op_num_threads = 1
        self.intra_op_num_threads = 1


class NodeArg:
    def __init__(self, name: str, shape, type_str: str):
        self.name = name
        self.shape = shape
        self.type = type_str


def _ort_version():
    """真实的 ORT 版本号来自 Kotlin 侧（ai.onnxruntime），拿不到时给个保守值。"""
    try:
        return str(jclass("ai.onnxruntime.OrtEnvironment").getVersion())
    except Exception:
        return "1.20.0"


__version__ = _ort_version()


def set_default_logger_severity(level):  # noqa: D103 - 与原 onnxruntime 同名同签名
    """原项目用它调低 ORT 日志级别；这里由 Kotlin 侧控制，忽略即可。"""
    return None


def get_available_providers():
    try:
        return list(_engine().availableProviders())
    except Exception:
        return ["CPUExecutionProvider"]


def get_device():
    return "CPU"


# ---------------------------------------------------------------------------
# 张量序列化
# ---------------------------------------------------------------------------

_DTYPE_TAG = {
    np.dtype("float32"): "float32",
    np.dtype("float64"): "float64",
    np.dtype("int64"): "int64",
    np.dtype("int32"): "int32",
    np.dtype("bool"): "bool",
    np.dtype("float16"): "float16",
    np.dtype("uint8"): "uint8",
}


def _dtype_tag(arr: np.ndarray) -> str:
    tag = _DTYPE_TAG.get(arr.dtype)
    if tag is None:
        raise TypeError(f"不支持的张量类型: {arr.dtype}")
    return tag


def _write_tensor(path: str, arr: np.ndarray) -> dict:
    arr = np.ascontiguousarray(arr)
    if arr.dtype == np.bool_:
        arr.tofile(path)  # bool 存成 1 字节
    else:
        arr.tofile(path)
    return {
        "shape": [int(v) for v in arr.shape],
        "dtype": _dtype_tag(arr),
        "file": path,
    }


def _read_tensor(path: str, dtype: str, shape) -> np.ndarray:
    np_dtype = {
        "float32": np.float32,
        "float64": np.float64,
        "int64": np.int64,
        "int32": np.int32,
        "bool": np.bool_,
        "float16": np.float16,
        "uint8": np.uint8,
    }.get(dtype)
    if np_dtype is None:
        raise TypeError(f"Kotlin 返回了不支持的类型: {dtype}")
    arr = np.fromfile(path, dtype=np_dtype)
    if shape:
        arr = arr.reshape(shape)
    return arr


# ---------------------------------------------------------------------------
# Session
# ---------------------------------------------------------------------------

class InferenceSession:
    def __init__(self, path, sess_options=None, providers=None, provider_options=None, **kwargs):
        self.path = os.fspath(path)
        self._meta = json.loads(str(_engine().describe(self.path)))
        self._inputs = [
            NodeArg(i["name"], i.get("shape"), i.get("dtype", "float32"))
            for i in self._meta.get("inputs", [])
        ]
        self._outputs = [
            NodeArg(o["name"], o.get("shape"), o.get("dtype", "float32"))
            for o in self._meta.get("outputs", [])
        ]
        self._providers = list(get_available_providers())

    # ---- 元信息 ----
    def get_inputs(self):
        return self._inputs

    def get_outputs(self):
        return self._outputs

    def get_providers(self):
        return self._providers

    def get_modelmeta(self):
        return None

    # ---- 推理 ----
    def run(self, output_names, input_feed, run_options=None):
        tmp = tempfile.mkdtemp(prefix="ort_")
        try:
            feeds = []
            for idx, (name, value) in enumerate(input_feed.items()):
                arr = np.asarray(value)
                info = _write_tensor(os.path.join(tmp, f"in{idx}.bin"), arr)
                info["name"] = name
                feeds.append(info)

            requested = [o.name for o in self._outputs]
            if output_names:
                requested = [n for n in output_names if n in requested] or requested

            meta = {"inputs": feeds, "outputs": requested, "outDir": tmp}
            meta_path = os.path.join(tmp, "meta.json")
            with open(meta_path, "w", encoding="utf-8") as f:
                json.dump(meta, f)

            manifest = json.loads(str(_engine().execute(self.path, meta_path)))
            outs = []
            for item in manifest.get("outputs", []):
                outs.append(_read_tensor(
                    item["file"], item.get("dtype", "float32"), item.get("shape")
                ))
            return outs
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


def release_session(model_path):
    """显式释放 Kotlin 端缓存的 session（大批量任务结束后调用，回收内存）。"""
    try:
        _engine().release(os.fspath(model_path))
    except Exception:
        pass


def set_threads(n):
    """推理线程数；0 表示交给 Kotlin 侧按设备核数自动决定。"""
    try:
        _engine().setThreads(int(n))
    except Exception:
        pass
