#!/usr/bin/env bash
# zig, as rustc's linker for the Linux slots (bzl/zig.bzl hands this to the prelude's
# cxx_zig_toolchain in place of a downloaded distribution). Two rewrites, both of which
# cargo-zigbuild also makes; everything else rustc 1.98 puts on a linux-gnu link line is accepted
# by zig 0.16 as is.
#
#   * `zig c++` -> `zig cc`. The prelude links through `zig c++`, and on a cold cache zig then
#     compiles libc++ for the target first -- megabytes of warnings that rustc folds into one JSON
#     diagnostic line, which overflows the 64 KiB readline in the prelude's rustc driver. Nothing
#     here is C++; a Rust cdylib links with cc.
#   * drop -Wl,--fix-cortex-a53-843419, which rustc's aarch64-unknown-linux-gnu spec adds to
#     every link and zig's linker refuses.
#
#   zig-wrapper.sh ZIG <zig subcommand and args...>
set -eu
zig=$1; shift
sub=$1; shift
[ "$sub" = "c++" ] && sub=cc
args=()
for a in "$@"; do
  case "$a" in
    -Wl,--fix-cortex-a53-843419) ;;
    *) args+=("$a") ;;
  esac
done
exec "$zig" "$sub" "${args[@]}"
