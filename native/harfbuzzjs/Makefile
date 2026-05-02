MAKEFLAGS += -s

# Fork of harfbuzzjs's Makefile that builds against our checked-in
# HarfBuzz submodule (../harfbuzz/) and re-enables the COLR/CPAL paint
# API by undef'ing HB_NO_COLOR and HB_NO_PAINT in config-override.h.
# We don't build hb-subset.wasm — kotlin-harfbuzz doesn't need it.

CXX = em++

COMMON_CXXFLAGS = \
	-std=c++11 \
	-fno-exceptions \
	-fno-rtti \
	-fno-threadsafe-statics \
	-fvisibility-inlines-hidden \
	-Oz \
	-I. \
	-DHB_TINY \
	-DHB_USE_INTERNAL_QSORT \
	-DHB_EXPERIMENTAL_API

HB_CXXFLAGS = \
	$(COMMON_CXXFLAGS) \
	-flto \
	-DHB_CONFIG_OVERRIDE_H=\"config-override.h\"

# `--no-entry`           : we're a library, no main()
# `MODULARIZE` + `EXPORT_NAME=createHarfBuzz` : matches upstream's
#                          hbjs.js loader convention
# `EXPORTED_FUNCTIONS=@hb.symbols` : the wasm export allowlist; any C
#                          symbol not in this file is dead-stripped by lld
# `EXPORTED_RUNTIME_METHODS=@em.runtime` : Module.* helpers (HEAPU8,
#                          addFunction, etc.) the JS side needs
# `ALLOW_TABLE_GROWTH`   : required by Module.addFunction so we can
#                          register paint callbacks at runtime
HB_LDFLAGS = \
	--no-entry \
	-s MODULARIZE \
	-s EXPORT_NAME=createHarfBuzz \
	-s EXPORTED_FUNCTIONS=@hb.symbols \
	-s EXPORTED_RUNTIME_METHODS=@em.runtime \
	-s INITIAL_MEMORY=512KB \
	-s ALLOW_MEMORY_GROWTH \
	-s ALLOW_TABLE_GROWTH \
	-lexports.js

HB_SRCS = ../harfbuzz/src/harfbuzz.cc
HB_DEPS = config-override.h hb.symbols em.runtime exports.js
HB_TARGET = hb.js

.PHONY: all clean

all: $(HB_TARGET)

$(HB_TARGET): $(HB_SRCS) $(HB_DEPS)
	@echo "  CXX      $@"
	$(CXX) $(HB_CXXFLAGS) $(HB_LDFLAGS) -o $@ $(HB_SRCS)

clean:
	rm -f $(HB_TARGET) hb.wasm
