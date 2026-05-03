package com.mohamedrejeb.harfbuzz.core

/**
 * Helpers shared by [HbFont]'s JVM-family actuals - pull JNI shape results
 * into immutable Kotlin types and apply higher-level paragraph/draw flows on
 * top of the raw bridge.
 */
internal object ShapingHelpers {

    suspend fun shapeBufferIntoRun(
        font: HbFont,
        buffer: HbBuffer,
        features: List<HbFeature>,
        sizePx: Float,
    ): ShapedRun {
        check(!buffer.isClosed) { "hb object disposed" }
        val packed = packFeatures(features)
        HarfbuzzNative.shape(font.ptr, buffer.ptr, packed, features.size)

        val count = HarfbuzzNative.bufferGlyphCount(buffer.ptr)
        if (count == 0) return ShapedRun.EMPTY.copy(direction = buffer.direction, script = buffer.script)

        val infoArr = IntArray(count * 3)
        HarfbuzzNative.bufferReadGlyphInfo(buffer.ptr, infoArr, count)

        val posArr = FloatArray(count * 4)
        HarfbuzzNative.bufferReadGlyphPositions(buffer.ptr, posArr, count)

        return buildShapedRun(font, sizePx, buffer.direction, buffer.script, count, infoArr, posArr)
    }

    suspend fun shapeParagraph(
        font: HbFont,
        text: String,
        sizePx: Float,
        baseDirection: HbDirection,
        features: List<HbFeature>,
        language: HbLanguage,
    ): ShapedParagraph {
        if (text.isEmpty()) {
            return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)
        }

        val resolver = BidiResolver()
        val bidiRuns = resolver.resolve(text, baseDirection)
        if (bidiRuns.isEmpty()) {
            return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)
        }

        // Visual order: for an LTR base, runs go in resolved order; for an RTL
        // base, the highest-level RTL outer block is reversed. Proper visual
        // reordering is not yet implemented. First cut: flip if base is RTL.
        val visualOrder: List<BidiRun> = if (baseDirection == HbDirection.RTL || (baseDirection == HbDirection.AUTO && bidiRuns.firstOrNull()?.isRtl == true)) {
            bidiRuns.reversed()
        } else {
            bidiRuns
        }

        HbBuffer().use { buf ->
            val runs = mutableListOf<ShapedRun>()
            var totalAdvance = 0f
            var inkLeft = Float.POSITIVE_INFINITY
            var inkTop = Float.POSITIVE_INFINITY
            var inkRight = Float.NEGATIVE_INFINITY
            var inkBottom = Float.NEGATIVE_INFINITY

            for (run in visualOrder) {
                val runText = text.substring(run.start, run.end)
                if (runText.isEmpty()) continue

                buf.reset()
                buf.text = runText
                buf.direction = if (run.isRtl) HbDirection.RTL else HbDirection.LTR
                // Leave script as HbScript.AUTO (the buffer default after reset)
                // so HarfBuzz's hb_buffer_guess_segment_properties detects the
                // run's script from its codepoints. Setting UNKNOWN here would
                // suppress auto-detection and break the Arabic / Indic shapers.
                buf.language = language
                buf.features = features

                val shaped = font.shape(buf, sizePx)
                val offsetGlyphs = shaped.glyphs.map { it.copy(cluster = it.cluster + run.start) }
                val shifted = shaped.copy(glyphs = offsetGlyphs)
                runs.add(shifted)

                // Each run's ink rect is in run-local pen space (penX = 0 at
                // the run's start). Translate by the running totalAdvance so
                // the paragraph-level ink rect reflects the actual painted
                // area in paragraph coordinates - without this, multi-run
                // (mixed BiDi) paragraphs end up with the wrong ink box.
                if (!shifted.ink.isEmpty) {
                    inkLeft = minOf(inkLeft, totalAdvance + shifted.ink.left)
                    inkTop = minOf(inkTop, shifted.ink.top)
                    inkRight = maxOf(inkRight, totalAdvance + shifted.ink.right)
                    inkBottom = maxOf(inkBottom, shifted.ink.bottom)
                }
                totalAdvance += shifted.totalAdvance
            }

            val inkRect = if (inkRight > inkLeft) HbRect(inkLeft, inkTop, inkRight, inkBottom) else HbRect.EMPTY

            // Build logical↔visual maps. First cut: assume one-to-one; real
            // visual reordering is not yet implemented. Identity arrays are
            // pulled from `IdentityIntArrayCache` so paragraphs of the same
            // length share a single underlying IntArray.
            val n = text.length
            val logicalToVisual = identityIntArray(n)
            val visualToLogical = identityIntArray(n)

            return ShapedParagraph(
                runs = runs,
                baseDirection = if (baseDirection == HbDirection.AUTO) {
                    if (bidiRuns.firstOrNull()?.isRtl == true) HbDirection.RTL else HbDirection.LTR
                } else baseDirection,
                totalAdvance = totalAdvance,
                ink = inkRect,
                logical = HbRect(0f, 0f, totalAdvance, 0f),
                logicalToVisual = logicalToVisual,
                visualToLogical = visualToLogical,
            )
        }
    }

    fun drawGlyph(font: HbFont, glyphId: Int, sink: HbPathSink) {
        // Two-pass to size buffers correctly. Most glyphs fit in 64 ops; if not,
        // first call returns the negative required size and we grow.
        var capacity = 64
        var opCodes = ByteArray(capacity)
        var coords = FloatArray(capacity * 6)

        var written = HarfbuzzNative.fontDrawGlyph(font.ptr, glyphId, opCodes, coords, capacity)
        if (written < 0) {
            // -written is the required capacity in opcodes
            capacity = -written + 8
            opCodes = ByteArray(capacity)
            coords = FloatArray(capacity * 6)
            written = HarfbuzzNative.fontDrawGlyph(font.ptr, glyphId, opCodes, coords, capacity)
            if (written < 0) {
                // Should not happen - bail.
                return
            }
        }

        var ci = 0
        for (i in 0 until written) {
            when (opCodes[i].toInt()) {
                OP_MOVE -> {
                    sink.moveTo(coords[ci], coords[ci + 1])
                    ci += 2
                }
                OP_LINE -> {
                    sink.lineTo(coords[ci], coords[ci + 1])
                    ci += 2
                }
                OP_QUAD -> {
                    sink.quadTo(coords[ci], coords[ci + 1], coords[ci + 2], coords[ci + 3])
                    ci += 4
                }
                OP_CUBIC -> {
                    sink.cubicTo(
                        coords[ci], coords[ci + 1],
                        coords[ci + 2], coords[ci + 3],
                        coords[ci + 4], coords[ci + 5],
                    )
                    ci += 6
                }
                OP_CLOSE -> sink.close()
            }
        }
    }

    private suspend fun buildShapedRun(
        font: HbFont,
        sizePx: Float,
        direction: HbDirection,
        script: HbScript,
        count: Int,
        infoArr: IntArray,
        posArr: FloatArray,
    ): ShapedRun {
        val glyphs = ArrayList<GlyphInfo>(count)
        val positions = ArrayList<GlyphPosition>(count)
        var totalAdvance = 0f
        var inkLeft = Float.POSITIVE_INFINITY
        var inkTop = Float.POSITIVE_INFINITY
        var inkRight = Float.NEGATIVE_INFINITY
        var inkBottom = Float.NEGATIVE_INFINITY

        // Pull every glyph's extents in a single JNI border-cross instead
        // of `count` per-glyph hops. Saves ~count×(JNI ABI cost + dispatcher
        // round-trip) per paragraph; the win scales with run length.
        val gids = IntArray(count) { infoArr[it * 3] }
        val extentsList = font.glyphExtentsBatch(gids, sizePx)

        var penX = 0f
        var penY = 0f
        for (i in 0 until count) {
            val gid = gids[i]
            val cluster = infoArr[i * 3 + 1]
            val flags = infoArr[i * 3 + 2]
            glyphs.add(GlyphInfo(glyphId = gid, cluster = cluster, flags = flags))

            val xAdv = posArr[i * 4 + 0]
            val yAdv = posArr[i * 4 + 1]
            val xOff = posArr[i * 4 + 2]
            val yOff = posArr[i * 4 + 3]
            positions.add(GlyphPosition(xAdv, yAdv, xOff, yOff))

            // Ink box accumulation - pull glyph extents and translate by pen
            // position. HarfBuzz returns coordinates in font Y-up convention:
            //  • yBearing > 0  → top above baseline
            //  • yOff   > 0    → glyph shifted up by GPOS
            //  • height < 0    → distance from top to bottom (going *down* in
            //                    y-up means decreasing y, hence negative)
            // We want the rect in screen Y-down (positive = down), so flip
            // the sign of yOff, yBearing, and height. Without this the ink
            // rect lands on the wrong vertical position for any glyph with a
            // non-zero y_offset (Arabic GPOS marks) and the bottom never
            // reaches the descender on letters like g/j/p/q/y.
            val extents = extentsList[i]
            if (extents != null) {
                val gx = penX + xOff + extents.xBearing
                val gy = penY - yOff - extents.yBearing
                val gw = extents.width
                val gh = -extents.height
                inkLeft = minOf(inkLeft, gx)
                inkTop = minOf(inkTop, gy)
                inkRight = maxOf(inkRight, gx + gw)
                inkBottom = maxOf(inkBottom, gy + gh)
            }

            penX += xAdv
            penY += yAdv
            totalAdvance += xAdv
        }

        val ink = if (inkRight > inkLeft) HbRect(inkLeft, inkTop, inkRight, inkBottom) else HbRect.EMPTY
        val logical = HbRect(0f, 0f, totalAdvance, 0f)

        return ShapedRun(
            glyphs = glyphs,
            positions = positions,
            direction = direction,
            script = script,
            totalAdvance = totalAdvance,
            ink = ink,
            logical = logical,
        )
    }

    private fun packFeatures(features: List<HbFeature>): IntArray? {
        if (features.isEmpty()) return null
        val out = IntArray(features.size * 4)
        for (i in features.indices) {
            val f = features[i]
            out[i * 4 + 0] = f.tag.raw.toInt()
            out[i * 4 + 1] = f.value.toInt()
            out[i * 4 + 2] = f.start
            out[i * 4 + 3] = f.end
        }
        return out
    }

    private const val OP_MOVE = 1
    private const val OP_LINE = 2
    private const val OP_QUAD = 3
    private const val OP_CUBIC = 4
    private const val OP_CLOSE = 5
}
