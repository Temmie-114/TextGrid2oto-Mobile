package com.tg2oto.mobile.core

import java.io.File
import java.io.RandomAccessFile

/**
 * 直接扫描 ONNX 的 protobuf 结构，判断模型精度（fp32 / fp16 / int8）。
 *
 * 只读 tag、dims、data_type 这些短字段，权重本体用文件指针跳过，所以一个
 * 200MB 的模型大约 1 秒。不依赖任何 onnx 库 —— arm64 上没有可用的 wheel。
 */
object OnnxInspector {

    data class Info(
        val precision: String,
        val fp16Bytes: Long,
        val fp32Bytes: Long,
        val int8Tensors: Int,
        val nodes: Int
    )

    private val QUANT_OPS = setOf(
        "QuantizeLinear", "DequantizeLinear", "DynamicQuantizeLinear",
        "MatMulInteger", "QLinearMatMul", "QLinearConv", "ConvInteger"
    )

    fun inspect(file: File): Info? = try {
        if (!file.exists() || file.length() < 16) null
        else RandomAccessFile(file, "r").use { scan(it) }
    } catch (t: Throwable) {
        null
    }

    private fun scan(raf: RandomAccessFile): Info? {
        val end = raf.length()
        val top = Cursor(raf, 0, end)

        var gStart = -1L
        var gEnd = -1L
        while (top.pos < end) {
            val tag = top.tag()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 7 && wire == 2) {          // ModelProto.graph
                val len = top.readVarint()
                gStart = top.pos
                gEnd = minOf(top.pos + len, end)
                top.pos = gEnd
                break
            } else {
                top.skip(wire)
            }
        }
        if (gStart < 0 || gEnd <= gStart) return null

        var fp16 = 0L
        var fp32 = 0L
        var int8Tensors = 0
        var nodes = 0
        var quant = false

        val g = Cursor(raf, gStart, gEnd)
        while (g.pos < gEnd) {
            val tag = g.tag()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 2 -> {    // GraphProto.node
                    val len = g.readVarint()
                    val s = g.pos
                    val e = minOf(g.pos + len, gEnd)
                    nodes++
                    val n = Cursor(raf, s, e)
                    while (n.pos < e) {
                        val t2 = n.tag()
                        val f2 = t2 ushr 3
                        val w2 = t2 and 7
                        if (f2 == 4 && w2 == 2) {       // NodeProto.op_type
                            val sl = n.readVarint()
                            if (n.readString(sl.toInt()) in QUANT_OPS) quant = true
                        } else {
                            n.skip(w2)
                        }
                    }
                    g.pos = e
                }
                field == 5 && wire == 2 -> {    // GraphProto.initializer
                    val len = g.readVarint()
                    val s = g.pos
                    val e = minOf(g.pos + len, gEnd)
                    var elems = 1L
                    var dtype = 0
                    val t = Cursor(raf, s, e)
                    while (t.pos < e) {
                        val t3 = t.tag()
                        val f3 = t3 ushr 3
                        val w3 = t3 and 7
                        when {
                            f3 == 1 && w3 == 0 -> elems *= t.readVarint()   // dims
                            f3 == 2 && w3 == 0 -> dtype = t.readVarint().toInt()
                            else -> t.skip(w3)
                        }
                    }
                    when (dtype) {
                        10 -> fp16 += elems * 2L       // FLOAT16
                        1 -> fp32 += elems * 4L        // FLOAT
                        3 -> int8Tensors++             // INT8
                        2 -> int8Tensors++             // UINT8
                    }
                    g.pos = e
                }
                else -> g.skip(wire)
            }
        }

        val precision = when {
            quant -> "int8"
            fp16 > fp32 -> "fp16"
            fp32 > 0 -> "fp32"
            else -> "unknown"
        }
        return Info(precision, fp16, fp32, int8Tensors, nodes)
    }

    private class Cursor(val raf: RandomAccessFile, var pos: Long, val end: Long) {

        fun tag(): Int = readVarint().toInt()

        fun readVarint(): Long {
            var v = 0L
            var shift = 0
            while (shift < 64) {
                val b = readByte()
                v = v or ((b.toLong() and 0x7f) shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
            throw IllegalStateException("varint too long")
        }

        fun readString(len: Int): String {
            if (len < 0 || pos + len > end) throw IllegalStateException("range")
            val buf = ByteArray(len)
            raf.readFully(buf)
            pos += len
            return String(buf, Charsets.UTF_8)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> pos += readVarint()
                5 -> pos += 4
                else -> throw IllegalStateException("wire $wire")
            }
            if (pos > end) throw IllegalStateException("overflow")
        }

        private fun readByte(): Int {
            if (pos >= end) throw IllegalStateException("eof")
            val b = raf.read()
            if (b < 0) throw IllegalStateException("eof")
            pos++
            return b
        }
    }
}
