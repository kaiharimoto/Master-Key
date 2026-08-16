cmake_minimum_required(VERSION 3.22.1)
project(masterkey_synth C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe comes in as a prefab package from the AAR.
find_package(oboe REQUIRED CONFIG)

add_library(masterkey_synth SHARED
        synth.cpp
        tsf_impl.c
)

target_include_directories(masterkey_synth PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

target_link_libraries(masterkey_synth
        oboe::oboe
        android
        log
        m
)

# -O2 rather than -O3: the mixer is not the bottleneck and -O2 keeps the
# binary small. NDK r28+ already emits 16 KB-page-aligned output by default.
target_compile_options(masterkey_synth PRIVATE -O2 -Wall -fvisibility=hidden)
