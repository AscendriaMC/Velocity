#!/usr/bin/env bash
# Build mccrypt and install it as velocity-natives' cipher library.
#
# The loader in Natives.java tries "/<platform>/velocity-cipher.so" *first* and
# falls through to the OpenSSL-built variants if it fails to load, so installing
# under that name needs no Java changes and leaves the old path as a safety net.
#
#   ./build-native.sh          host only
#   ./build-native.sh all      host + Linux x86_64/aarch64 (cargo-zigbuild)
#
# Linux artifacts are cross-built against glibc 2.17 so they run anywhere the
# OpenSSL ones did; upstream builds theirs on Ubuntu 20.04 for the same reason.
set -euo pipefail

cd "$(dirname "$0")"
RES="../native/src/main/resources"
GLIBC=2.17

build() { # <rust-target> <artifact> <dest-dir> <dest-name>
  local target=$1 artifact=$2 dir=$3 name=$4
  if [[ $target == *linux* ]]; then
    cargo zigbuild --profile dist --target "$target"
  else
    cargo build --profile dist --target "$target"
  fi
  mkdir -p "$RES/$dir"
  cp "target/${target%%.*}/dist/$artifact" "$RES/$dir/$name"
  echo "  -> $RES/$dir/$name"
}

host_target=$(rustc -vV | awk '/^host:/ {print $2}')
# NB: the resource directory is "macos_arm64", not "macos_aarch64" — it follows
# `uname -m` on macOS, not the Rust triple. Getting this wrong is silent: the
# loader just falls through to the next variant.
case "$host_target" in
  aarch64-apple-darwin) build "$host_target" libmccrypt.dylib macos_arm64   velocity-cipher.dylib ;;
  x86_64-unknown-linux-gnu)  build "$host_target" libmccrypt.so linux_x86_64  velocity-cipher.so ;;
  aarch64-unknown-linux-gnu) build "$host_target" libmccrypt.so linux_aarch64 velocity-cipher.so ;;
  *) echo "unsupported host $host_target" >&2; exit 1 ;;
esac

if [[ "${1:-}" == "all" ]]; then
  build "x86_64-unknown-linux-gnu.$GLIBC"  libmccrypt.so linux_x86_64  velocity-cipher.so
  build "aarch64-unknown-linux-gnu.$GLIBC" libmccrypt.so linux_aarch64 velocity-cipher.so
  # musl deliberately not installed: NativeConstraints routes musl hosts only to
  # the "-ossl30x-musl" names, so shipping there means either clobbering an
  # OpenSSL-named file or adding a variant in Natives.java. Left for later.
fi
