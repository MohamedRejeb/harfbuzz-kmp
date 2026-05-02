// Local fork of harfbuzzjs config-override.h adding the COLR/CPAL paint
// API back in. The upstream npm build sets HB_TINY which #defines both
// HB_NO_COLOR and HB_NO_PAINT (see harfbuzz/src/hb-config.hh) — without
// these undefs the wasm binary ships with no `hb_ot_color_*` or
// `hb_paint_*` symbols, and our COLR v0 / v1 code on Wasm has no
// HarfBuzz to call.

#undef HB_NO_CFF
#undef HB_NO_OT_FONT_CFF
#undef HB_NO_DRAW
#undef HB_NO_BUFFER_MESSAGE
#undef HB_NO_BUFFER_SERIALIZE
#undef HB_NO_VAR
#undef HB_NO_OT_FONT_GLYPH_NAMES
#undef HB_NO_FACE_COLLECT_UNICODES
#undef HB_NO_AVAR2
#undef HB_NO_CUBIC_GLYF
#undef HB_NO_VAR_COMPOSITES
#undef HB_NO_NAME
#undef HB_NO_LAYOUT_FEATURE_PARAMS

// Color glyph + paint tree support. These re-enable hb_ot_color_*,
// hb_color_line_*, and the full hb_paint_funcs_t API.
#undef HB_NO_COLOR
#undef HB_NO_PAINT

// SVG-in-OT table support. Without this, hb_ot_color_has_svg returns
// false and hb_ot_color_glyph_reference_svg always returns an empty
// blob, even for fonts that ship the table (Aref Ruqaa Ink, Noto Color
// Emoji). Adds ~5KB to the wasm binary in exchange for higher-fidelity
// rendering of fonts that prefer SVG to COLR.
#undef HB_NO_SVG

#define HB_BUFFER_MESSAGE_MORE 1
