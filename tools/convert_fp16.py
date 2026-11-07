"""把 fp32 ONNX 转成 fp16（权重半精度，IO 保持 fp32，方便 Android 端直接替换）。

用法: python3 tools/convert_fp16.py <in.onnx> <out.onnx>
"""
import sys
import onnx
from onnxconverter_common import float16

# onnxconverter-common 在新版 onnx 上 remove_unnecessary_cast_node 会崩溃，
# 该函数只是清理冗余 Cast，跳过不影响正确性。
float16.remove_unnecessary_cast_node = lambda graph: None

# CPU EP 对 Conv 等算子没有 fp16 kernel，这些算子保留 fp32
BLOCK = [
    "Conv", "ConvTranspose", "BatchNormalization", "LayerNormalization",
    "Softmax", "ReduceMean", "Exp", "Pow", "Sqrt", "Div", "Erf", "Gelu",
    "NonMaxSuppression", "TopK", "RoiAlign", "Resize", "Range",
    "CumSum", "ArgMax", "ArgMin", "Upsample", "Pad", "Slice", "Gather",
    "GatherND", "ScatterND", "InstanceNormalization", "GroupNormalization",
]


def main():
    src, dst = sys.argv[1], sys.argv[2]
    print(f"加载 {src}")
    model = onnx.load(src)
    print(f"  原始大小 {len(str(model.graph)) and 0} 节点数 {len(model.graph.node)}")
    model16 = float16.convert_float_to_float16(
        model,
        keep_io_types=True,
        disable_shape_infer=True,
        op_block_list=BLOCK,
        check_fp16_ready=False,
    )
    onnx.save(model16, dst)
    print(f"已写出 {dst}")


if __name__ == "__main__":
    main()
