"""权重级 fp16 压缩：initializer 存 fp16，消费处插 Cast 回转 fp32。

相比整图 fp16，这个方案在 Android CPU EP 上 100% 兼容（计算仍是 fp32），
体积同样减半，精度损失只有 fp16 尾数（~1e-3 相对）。

用法: python3 convert_weight_fp16.py <in.onnx> <out.onnx>
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def convert(src: str, dst: str):
    model = onnx.load(src)
    graph = model.graph

    keep = []
    cast_nodes = []
    converted = 0
    saved_bytes = 0

    for init in list(graph.initializer):
        if init.data_type != TensorProto.FLOAT or init.name in {"", None}:
            keep.append(init)
            continue
        arr = numpy_helper.to_array(init)
        if arr.size < 1024:  # 小张量不值得转换
            keep.append(init)
            continue
        half = arr.astype(np.float16)
        half_init = numpy_helper.from_array(half, init.name + "__fp16")
        cast_nodes.append(
            helper.make_node(
                "Cast",
                [init.name + "__fp16"],
                [init.name],
                to=TensorProto.FLOAT,
                name="wcast_" + init.name.replace("/", "_"),
            )
        )
        keep.append(half_init)
        converted += 1
        saved_bytes += arr.nbytes - half.nbytes

    # 用新的 initializer 列表（原 fp32 常量被 fp16 + Cast 取代）
    del graph.initializer[:]
    graph.initializer.extend(keep)

    # Cast 节点插在图最前面，保证拓扑合法
    nodes = list(graph.node)
    for node in reversed(cast_nodes):
        nodes.insert(0, node)
    del graph.node[:]
    graph.node.extend(nodes)

    onnx.save(model, dst)
    print(f"转换 {converted} 个权重，节省 {saved_bytes / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2])
