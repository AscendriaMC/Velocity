#!/bin/sh
# javac + jar in one action. javac creates -d itself but not -s, and the jar tool wants its
# inputs as directories, so the small amount of glue lives here rather than in starlark.
#
#   javac.sh JAVAC JAR CLASSES GEN OUT_JAR RESOURCES_DIR|- -- <javac args...>
set -eu
JAVAC=$1; JARTOOL=$2; CLASSES=$3; GEN=$4; OUT=$5; RES=$6; shift 6
[ "$1" = "--" ] && shift
mkdir -p "$CLASSES" "$GEN"
"$JAVAC" -d "$CLASSES" -s "$GEN" "$@"
if [ "$RES" != "-" ]; then
  "$JARTOOL" --create --file "$OUT" --no-manifest -C "$CLASSES" . -C "$RES" .
else
  "$JARTOOL" --create --file "$OUT" --no-manifest -C "$CLASSES" .
fi
