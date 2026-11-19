# Duvo - remote control and streaming for a Windows PC.
# Copyright (C) 2026 Duvo contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

# libmoonlight-core equivalent for Duvo: moonlight-common-c (+ ENet, nanors) and our JNI bridge,
# linked statically against prebuilt OpenSSL libcrypto and libopus (see README.md).
cmake_minimum_required(VERSION 3.22.1)
project(duvo LANGUAGES C)

set(CMAKE_C_STANDARD 11)

set(MLC ${CMAKE_CURRENT_SOURCE_DIR}/moonlight-common-c)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

if(NOT EXISTS ${MLC}/src/Limelight.h OR NOT EXISTS ${MLC}/enet/include/enet/enet.h)
    message(FATAL_ERROR "moonlight-common-c submodule missing: run `git submodule update --init --recursive`")
endif()

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY}/openssl/${ANDROID_ABI}/libcrypto.a
    INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY}/openssl/include)

add_library(opus STATIC IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY}/opus/${ANDROID_ABI}/libopus.a
    INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY}/opus/include)

# Same source list as Moonlight Android's moonlight-core/Android.mk.
add_library(duvo SHARED
    ${MLC}/src/AudioStream.c
    ${MLC}/src/ByteBuffer.c
    ${MLC}/src/Connection.c
    ${MLC}/src/ConnectionTester.c
    ${MLC}/src/ControlStream.c
    ${MLC}/src/FakeCallbacks.c
    ${MLC}/src/InputStream.c
    ${MLC}/src/LinkedBlockingQueue.c
    ${MLC}/src/Misc.c
    ${MLC}/src/Platform.c
    ${MLC}/src/PlatformCrypto.c
    ${MLC}/src/PlatformSockets.c
    ${MLC}/src/RtpAudioQueue.c
    ${MLC}/src/RtpVideoQueue.c
    ${MLC}/src/RtspConnection.c
    ${MLC}/src/RtspParser.c
    ${MLC}/src/SdpGenerator.c
    ${MLC}/src/SimpleStun.c
    ${MLC}/src/VideoDepacketizer.c
    ${MLC}/src/VideoStream.c
    ${MLC}/nanors/deps/obl/oblas_common.c
    ${MLC}/nanors/deps/obl/oblas_lite.c
    ${MLC}/nanors/rs.c
    ${MLC}/enet/callbacks.c
    ${MLC}/enet/compress.c
    ${MLC}/enet/host.c
    ${MLC}/enet/list.c
    ${MLC}/enet/packet.c
    ${MLC}/enet/peer.c
    ${MLC}/enet/protocol.c
    ${MLC}/enet/unix.c
    duvo_jni.c
)

target_include_directories(duvo PRIVATE
    ${MLC}/enet/include
    ${MLC}/nanors/deps/obl
    ${MLC}/nanors
    ${MLC}/src)

target_compile_definitions(duvo PRIVATE HAS_SOCKLEN_T=1 LC_ANDROID HAVE_CLOCK_GETTIME=1
    $<$<CONFIG:Debug>:LC_DEBUG> $<$<CONFIG:Debug>:DUVO_TEST_HOOKS>)

target_compile_options(duvo PRIVATE -Wno-unused-parameter)

target_link_libraries(duvo PRIVATE crypto opus log android)

# Keep the static libs' symbols private to libmoonlight; 16 KB page alignment for Android 15+.
target_link_options(duvo PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
