package com.mohamedrejeb.harfbuzz.core

/**
 * Decodes the byte buffer produced by `fontPaintGlyph` (see
 * `harfbuzz_jni.cpp`'s wire-format comment) and dispatches each operation
 * into an [HbPaintSink].
 *
 * The buffer is little-endian - JVM x86/ARM, iOS, and Wasm all use that
 * byte order, and the JNI side writes in native order on the same host -
 * so no byte-swap is needed. Reading past the end indicates a
 * serialisation/wire-format mismatch; the parser fails fast with the
 * offending offset rather than continuing into garbage.
 */
public object PaintBufferParser {

    private const val OP_PUSH_TRANSFORM   = 1
    private const val OP_POP_TRANSFORM    = 2
    private const val OP_PUSH_CLIP_GLYPH  = 3
    private const val OP_PUSH_CLIP_RECT   = 4
    private const val OP_POP_CLIP         = 5
    private const val OP_COLOR            = 6
    private const val OP_LINEAR_GRADIENT  = 7
    private const val OP_RADIAL_GRADIENT  = 8
    private const val OP_SWEEP_GRADIENT   = 9
    private const val OP_PUSH_GROUP       = 10
    private const val OP_POP_GROUP        = 11
    private const val OP_IMAGE            = 12

    public fun dispatch(bytes: ByteArray, sink: HbPaintSink) {
        if (bytes.isEmpty()) return
        Cursor(bytes).dispatchAll(sink)
    }

    private class Cursor(private val data: ByteArray) {
        private var pos: Int = 0

        fun dispatchAll(sink: HbPaintSink) {
            while (pos < data.size) {
                when (val op = readU8()) {
                    OP_PUSH_TRANSFORM -> sink.pushTransform(
                        readF32(), readF32(), readF32(), readF32(), readF32(), readF32(),
                    )
                    OP_POP_TRANSFORM -> sink.popTransform()
                    OP_PUSH_CLIP_GLYPH -> sink.pushClipGlyph(readI32())
                    OP_PUSH_CLIP_RECT -> sink.pushClipRectangle(
                        readF32(), readF32(), readF32(), readF32(),
                    )
                    OP_POP_CLIP -> sink.popClip()
                    OP_COLOR -> {
                        val isForeground = readU8() != 0
                        val argb = readI32()
                        sink.color(isForeground, argb)
                    }
                    OP_LINEAR_GRADIENT -> {
                        val x0 = readF32(); val y0 = readF32()
                        val x1 = readF32(); val y1 = readF32()
                        val x2 = readF32(); val y2 = readF32()
                        val extend = GradientExtend.fromHbValue(readU8())
                        val stops = readStops()
                        sink.linearGradient(x0, y0, x1, y1, x2, y2, extend, stops)
                    }
                    OP_RADIAL_GRADIENT -> {
                        val x0 = readF32(); val y0 = readF32(); val r0 = readF32()
                        val x1 = readF32(); val y1 = readF32(); val r1 = readF32()
                        val extend = GradientExtend.fromHbValue(readU8())
                        val stops = readStops()
                        sink.radialGradient(x0, y0, r0, x1, y1, r1, extend, stops)
                    }
                    OP_SWEEP_GRADIENT -> {
                        val cx = readF32(); val cy = readF32()
                        val start = readF32(); val end = readF32()
                        val extend = GradientExtend.fromHbValue(readU8())
                        val stops = readStops()
                        sink.sweepGradient(cx, cy, start, end, extend, stops)
                    }
                    OP_PUSH_GROUP -> sink.pushGroup()
                    OP_POP_GROUP -> sink.popGroup(CompositeMode.fromHbValue(readI32()))
                    OP_IMAGE -> {
                        val width = readI32()
                        val height = readI32()
                        val formatTag = readI32()
                        val slant = readF32()
                        val extents = if (readU8() != 0) {
                            PaintImageExtents(readF32(), readF32(), readF32(), readF32())
                        } else {
                            null
                        }
                        val data = readBytes(readI32())
                        sink.image(
                            PaintImage(
                                data = data,
                                width = width,
                                height = height,
                                formatTag = formatTag,
                                slant = slant,
                                extents = extents,
                            ),
                        )
                    }
                    else -> error("Unknown paint opcode: $op (offset ${pos - 1})")
                }
            }
        }

        private fun readStops(): List<ColorStop> {
            val count = readI32()
            if (count <= 0) return emptyList()
            return List(count) {
                val offset = readF32()
                val isForeground = readU8() != 0
                val argb = readI32()
                ColorStop(offset = offset, isForeground = isForeground, argb = argb)
            }
        }

        private fun readU8(): Int {
            require(pos < data.size) { "paint buffer underrun at offset $pos" }
            return data[pos++].toInt() and 0xFF
        }

        private fun readI32(): Int {
            require(pos + 4 <= data.size) { "paint buffer underrun at offset $pos" }
            val b0 = data[pos].toInt() and 0xFF
            val b1 = data[pos + 1].toInt() and 0xFF
            val b2 = data[pos + 2].toInt() and 0xFF
            val b3 = data[pos + 3].toInt() and 0xFF
            pos += 4
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        private fun readF32(): Float = Float.fromBits(readI32())

        private fun readBytes(count: Int): ByteArray {
            require(count >= 0 && pos + count <= data.size) {
                "paint buffer underrun at offset $pos (want $count bytes)"
            }
            val out = data.copyOfRange(pos, pos + count)
            pos += count
            return out
        }
    }
}
