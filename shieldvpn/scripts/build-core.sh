#!/usr/bin/env bash
# Builds the Rust core (Brave's adblock-rust wrapped in JNI) for all Android ABIs
# and drops libshieldcore.so into app/src/main/jniLibs/<abi>/.
#
# Needs: rustup (stable) with the Android targets, cargo-ndk, and an Android NDK (r26+).
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   cargo install cargo-ndk
set -euo pipefail
cd "$(dirname "$0")/../core"

: "${ANDROID_NDK_HOME:=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "Set ANDROID_NDK_HOME to your NDK directory" >&2; exit 1; }
export ANDROID_NDK_HOME

# Keep local paths (home dir, username) out of the shipped .so: rustc embeds source paths
# for panic messages, so remap them to neutral prefixes.
CORE_DIR="$(pwd)"
export RUSTFLAGS="${RUSTFLAGS:-} \
  --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo \
  --remap-path-prefix=${RUSTUP_HOME:-$HOME/.rustup}=/rustup \
  --remap-path-prefix=${CORE_DIR}=/src/core"

cargo test -j2
cargo ndk -P 26 -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../app/src/main/jniLibs build --release -j2
ls -lh ../app/src/main/jniLibs/*/libshieldcore.so
