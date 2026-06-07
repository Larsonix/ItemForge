#!/usr/bin/env bash
# verify-sources.sh — lint .claude/SOURCE_MAP.md against the real filesystem.
#
# Two failure modes this guards against (both have wasted real time):
#   FICTION  — the map names a path that does NOT exist on disk (→ hard error, exit 1).
#   OMISSION — a reference dir exists under ../ or ../APIReference/ but is never mentioned
#              in the map (→ warning; a human decides whether it's a knowledge source).
#
# The map is the spec; the filesystem is ground truth; this checks they agree. Run after any
# change to the reference tree or the map. Derives all expectations FROM the map + the tree,
# so it never holds a hardcoded list that can itself drift.
#
# Usage:  bash scripts/verify-sources.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAP="$ROOT/.claude/SOURCE_MAP.md"
cd "$ROOT" || { echo "cannot cd to project root"; exit 2; }
[ -f "$MAP" ] || { echo "FATAL: $MAP not found"; exit 2; }

fail=0
warn=0

echo "== FICTION check: every ../path the map claims must exist =="
# Pull ../-relative path tokens out of the map, strip trailing markdown/glob noise, dedupe.
# Neutralize `...` ellipsis first so a markdown example (`a/.../b`, `~/.../b`) can't yield a
# bogus `../b`. A real `../foo` has exactly two dots and survives. (git-bash grep -P is
# unreliable here, so this stays POSIX -E.)
paths=$(sed -E 's/\.\.\./###/g' "$MAP" \
        | grep -oE '\.\./[A-Za-z0-9._@/{},*-]+' \
        | sed -E 's/[`,.)]+$//' \
        | sort -u)
while IFS= read -r p; do
  [ -z "$p" ] && continue
  # Globs / brace-expansions / wildcards: verify the nearest concrete ancestor dir exists.
  # Take everything before the first glob/brace char; if that ends at a path separator it's a
  # clean dir, otherwise the glob was mid-segment (e.g. Server-*.jar) so drop to its dirname.
  check="$p"
  case "$p" in
    *'{'*|*'*'*)
      prefix="${p%%[{*]*}"
      case "$prefix" in
        */) check="${prefix%/}" ;;
        *)  check="${prefix%/*}" ;;
      esac
      ;;
  esac
  if [ -e "$check" ]; then
    :
  else
    echo "  FICTION: map references '$p' but '$check' does not exist"
    fail=1
  fi
done <<< "$paths"
[ "$fail" -eq 0 ] && echo "  ok — no fictional paths"

echo
echo "== OMISSION check: reference dirs not mentioned in the map =="
# Dirs we never treat as knowledge sources (build artifacts, tooling, infra, test servers).
ignore_re='^(build|bin|gradle|tools|ghidra-projects|downloads|stubs|extracted|com|decompiled|scripts|servertest|testserveritemforge|itemforge|\.git|node_modules|\.gradle|\.idea)$'
for base in ".." "../APIReference"; do
  [ -d "$base" ] || continue
  for d in "$base"/*/; do
    name="$(basename "$d")"
    echo "$name" | grep -qiE "$ignore_re" && continue
    # ItemForge itself + its own siblings handled by section 8 broadly; only flag if the
    # name appears nowhere in the map.
    if ! grep -q "$name" "$MAP"; then
      echo "  OMISSION?: '$base/$name' is on disk but not mentioned in SOURCE_MAP.md"
      warn=1
    fi
  done
done
[ "$warn" -eq 0 ] && echo "  ok — every reference dir is accounted for"

echo
if [ "$fail" -ne 0 ]; then
  echo "RESULT: FAIL — the map claims paths that don't exist. Fix the map."
  exit 1
fi
if [ "$warn" -ne 0 ]; then
  echo "RESULT: PASS with warnings — review the OMISSION? lines; add real sources to the map."
  exit 0
fi
echo "RESULT: PASS — SOURCE_MAP.md matches the filesystem."
