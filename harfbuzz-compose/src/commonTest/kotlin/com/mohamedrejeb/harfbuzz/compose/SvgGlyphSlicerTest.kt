package com.mohamedrejeb.harfbuzz.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the SVG-in-OT slicer used by [renderSvgGlyph]. Runs
 * on every Compose Multiplatform target (commonTest), so the slicer's
 * regex-free string scanner gets exercised under both JVM (where the
 * goldens pin visual output) and Wasm (where it's the only line of
 * defence - there are no Wasm goldens yet).
 *
 * The fixtures mirror the two real-world shapes the slicer handles in
 * production:
 *   - Aref Ruqaa Ink: tiny single-document, two `<g>` siblings sharing
 *     a path. Slicer should return null (single-glyph fast path).
 *   - Noto Color Emoji: multi-glyph document with each glyph in its
 *     own `<g id="glyphN">` sibling. Slicer should pick the target
 *     and drop everything else.
 */
class SvgGlyphSlicerTest {

    @Test
    fun singleGlyphDocReturnsNull() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" version="1.1">
              <defs><path id="p1" d="M0 0 L10 10 Z" fill="red"/></defs>
              <g id="glyph42"><use xlink:href="#p1"/></g>
            </svg>
        """.trimIndent().encodeToByteArray()
        val sliced = SvgGlyphSlicer.sliceGlyph(svg, 42)
        assertNull(
            sliced,
            "single-glyph documents should hit the fast path (slicer returns null and caller renders the original bytes)",
        )
    }

    @Test
    fun multiGlyphDocSlicesToOneTarget() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" version="1.1">
              <defs>
                <path id="pA" d="M0 0 L10 0" fill="blue"/>
                <path id="pB" d="M0 0 L0 10" fill="green"/>
              </defs>
              <g id="glyph1"><use xlink:href="#pA"/></g>
              <g id="glyph2"><use xlink:href="#pB"/></g>
              <g id="glyph3"><use xlink:href="#pA"/></g>
            </svg>
        """.trimIndent().encodeToByteArray()

        val sliced = SvgGlyphSlicer.sliceGlyph(svg, 2)
        assertNotNull(sliced, "multi-glyph slice should succeed")
        val str = sliced.decodeToString()
        assertTrue(str.contains("id=\"glyph2\""), "sliced SVG must contain target glyph")
        assertEquals(0, occurrences(str, "id=\"glyph1\""), "sibling glyph1 must be removed")
        assertEquals(0, occurrences(str, "id=\"glyph3\""), "sibling glyph3 must be removed")
        assertTrue(str.contains("<defs"), "sliced SVG should preserve the original defs block")
        assertTrue(str.startsWith("<svg"), "sliced SVG should start with <svg")
        assertTrue(str.endsWith("</svg>"), "sliced SVG should be well-closed")
    }

    @Test
    fun missingTargetGlyphReturnsNull() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <defs><path id="p" d="M0 0"/></defs>
              <g id="glyph1"><use xlink:href="#p"/></g>
              <g id="glyph2"><use xlink:href="#p"/></g>
            </svg>
        """.trimIndent().encodeToByteArray()
        assertNull(
            SvgGlyphSlicer.sliceGlyph(svg, 999),
            "slice should return null when the target glyph isn't present",
        )
    }

    @Test
    fun nestedGElementsAreClosedAtTheRightDepth() {
        // The slicer's depth counter must distinguish the outer <g> from
        // its nested children. If it doesn't, the wrong </g> closes the
        // slice and the next sibling leaks in.
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <defs><path id="p" d="M0 0"/></defs>
              <g id="glyph1">
                <g transform="translate(10,10)">
                  <use xlink:href="#p"/>
                </g>
              </g>
              <g id="glyph2"><use xlink:href="#p"/></g>
            </svg>
        """.trimIndent().encodeToByteArray()

        val sliced = SvgGlyphSlicer.sliceGlyph(svg, 1)
        assertNotNull(sliced)
        val str = sliced.decodeToString()
        assertTrue(str.contains("transform=\"translate(10,10)\""))
        assertEquals(
            0,
            occurrences(str, "id=\"glyph2\""),
            "depth tracking should keep glyph2 out of the glyph1 slice",
        )
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var i = 0
        var count = 0
        while (true) {
            val next = haystack.indexOf(needle, i)
            if (next < 0) return count
            count++
            i = next + needle.length
        }
    }
}
