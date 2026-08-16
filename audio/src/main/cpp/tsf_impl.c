/*
 * Single translation unit that instantiates TinySoundFont.
 *
 * tsf.h is a header-only library: exactly one file in the project may define
 * TSF_IMPLEMENTATION, and it must be compiled as C (not C++) so stb_vorbis,
 * which tsf.h vendors for SF3 support, compiles cleanly.
 */
#define TSF_IMPLEMENTATION
#include "third_party/tsf.h"
