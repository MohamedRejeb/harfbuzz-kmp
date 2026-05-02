// harfbuzzjs ships an Emscripten-generated `hb.js` wrapper that has Node.js
// fallbacks (`require('fs')`, `require('path')`, etc.). Webpack's browser
// config doesn't ship those modules; tell it to silently use `false` for the
// fallbacks and harfbuzzjs picks the browser code path at runtime.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
    fs: false,
    path: false,
    crypto: false,
});

// Emscripten's `hb.js` does `fetch('hb.wasm')` against its script directory
// at runtime. Webpack doesn't see that as a static dependency, so without
// help the binary never reaches the dist dir. Copy it to the output root so
// the default fetch URL just resolves.
//
// We pull from `kotlin-harfbuzzjs` — our locally-built fork that re-enables
// HarfBuzz's COLR/CPAL paint API (the upstream `harfbuzzjs` npm strips it
// via HB_TINY).  See `native/harfbuzzjs/Makefile` for the build.
//
// We resolve via __dirname instead of `require.resolve('kotlin-harfbuzzjs/...')`
// because the Kotlin Wasm pipeline runs webpack from the kotlin-npm-tooling
// cache. From there, Node's lookup ascends from a different root and fails
// to find our workspace package — but `__dirname` of this config file is
// always `build/wasm/packages/kotlin-harfbuzz-sample/`, two levels above
// `build/wasm/node_modules/`.
const path = require('path');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const hbWasmPath = path.resolve(
    __dirname,
    '..', '..', 'node_modules', 'kotlin-harfbuzzjs', 'hb.wasm',
);
const hbWorkerPath = path.resolve(
    __dirname,
    '..', '..', 'node_modules', 'kotlin-harfbuzzjs', 'hb-worker.js',
);
// hb.js and hbjs.js are imported by hb-worker.js as ES modules at runtime,
// so they must land in the same output directory as the worker.
const hbJsPath = path.resolve(
    __dirname,
    '..', '..', 'node_modules', 'kotlin-harfbuzzjs', 'hb.js',
);
const hbjsJsPath = path.resolve(
    __dirname,
    '..', '..', 'node_modules', 'kotlin-harfbuzzjs', 'hbjs.js',
);
config.plugins = (config.plugins || []).concat([
    new CopyWebpackPlugin({
        patterns: [
            { from: hbWasmPath, to: 'hb.wasm' },
            { from: hbWorkerPath, to: 'hb-worker.js' },
            { from: hbJsPath, to: 'hb.js' },
            { from: hbjsJsPath, to: 'hbjs.js' },
        ],
    }),
]);
