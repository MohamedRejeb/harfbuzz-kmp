// Empty libexports.js — present so the `-lexports.js` linker flag
// resolves. The flag itself is what we want: it triggers Emscripten's
// link.py to set MINIFY_WASM_EXPORT_NAMES = 0, which preserves the
// readable C function names on `Module.wasmExports.*`. Without that,
// upstream's hbjs.js (which calls `exports.malloc`, `exports.hb_*`)
// would fail because every export becomes a single-letter alias.
//
// Upstream harfbuzzjs uses the same trick — see
// link.py:`if not settings.DECLARE_ASM_MODULE_EXPORTS or '-lexports.js'
// in linker_args: settings.MINIFY_WASM_EXPORT_NAMES = 0`.
mergeInto(LibraryManager.library, {});
