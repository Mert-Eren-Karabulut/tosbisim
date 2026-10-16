# simrelayd — SIM Relay host audio daemon (GPL-3.0-or-later). Build: ./build.sh (NDK, arm64-v8a).
cmake_minimum_required(VERSION 3.22)
project(simrelayd C)
set(CMAKE_C_STANDARD 11)

# libopus 1.5.2 (BSD) — voice codec. Fixed-point build is fastest on phones without FPU concerns; we
# keep float (arm64 has a good FPU) and NEON intrinsics enabled by opus's own detection.
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/opus-1.5.2 opus EXCLUDE_FROM_ALL)

# PCM I/O: the phone's own /system/lib64/libtinyalsa.so, loaded at runtime (see audio.c ta_load).

add_executable(simrelayd simrelayd.c audio.c box.c)
target_compile_options(simrelayd PRIVATE -Wall -Wextra -Werror)
target_link_libraries(simrelayd PRIVATE opus log m dl)
