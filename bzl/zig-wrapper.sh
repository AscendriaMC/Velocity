#!/usr/bin/env bash
# zig, as rustc's linker for the shipping slots (bzl/zig.bzl). Two rewrites, both of which
# cargo-zigbuild also makes; everything else rustc 1.98 puts on a link line is accepted by zig
# 0.16 as is:
#
#   * drop -Wl,--fix-cortex-a53-843419, which rustc's aarch64-unknown-linux-gnu spec adds to
#     every link and zig's linker refuses;
#   * drop -Wl,-oso_prefix,<dir>, which the prelude adds to every darwin link (relative debug
#     paths for dSYMs) and zig's Mach-O linker does not implement.
#
# The prelude passes its own linker flags through an @response-file, so those are expanded here
# (one argument per line) before filtering; zig would otherwise read the file itself and see the
# flag. `zig c++` -> `zig cc` is kept as a safety net: on a cold cache `zig c++` compiles libc++
# for the target first, and its warnings overflow the prelude's rustc driver.
#
#   zig-wrapper.sh ZIG <zig subcommand and args...>
set -eu
zig=$1; shift
sub=$1; shift
[ "$sub" = "c++" ] && sub=cc
args=()
keep() {
  case "$1" in
    -Wl,--fix-cortex-a53-843419) ;;
    -Wl,-oso_prefix,*) ;;
    *) args+=("$1") ;;
  esac
}
for a in "$@"; do
  case "$a" in
    @*)
      f=${a#@}
      if [ -f "$f" ]; then
        while IFS= read -r line || [ -n "$line" ]; do
          [ -n "$line" ] && keep "$line"
        done < "$f"
      else
        keep "$a"
      fi
      ;;
    *) keep "$a" ;;
  esac
done
exec "$zig" "$sub" "${args[@]}"
