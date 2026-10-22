#!/usr/bin/env bash
# Downloads the libraries the player needs (Media3 ExoPlayer with HLS, and what it depends on) into libs/, from
# Google's Maven repository and Maven Central, and checks each file against the SHA-1 the repository publishes.
# AARs are unpacked to their classes.jar. Runs from build.sh; skips files already there.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p libs

GOOGLE=https://dl.google.com/android/maven2
CENTRAL=https://repo1.maven.org/maven2
MEDIA3=1.11.1

# repo  group-path  artifact  version  type
LIBS="
$GOOGLE androidx/media3 media3-common $MEDIA3 aar
$GOOGLE androidx/media3 media3-container $MEDIA3 aar
$GOOGLE androidx/media3 media3-database $MEDIA3 aar
$GOOGLE androidx/media3 media3-datasource $MEDIA3 aar
$GOOGLE androidx/media3 media3-decoder $MEDIA3 aar
$GOOGLE androidx/media3 media3-extractor $MEDIA3 aar
$GOOGLE androidx/media3 media3-exoplayer $MEDIA3 aar
$GOOGLE androidx/media3 media3-exoplayer-hls $MEDIA3 aar
$GOOGLE androidx/annotation annotation 1.6.0 jar
$GOOGLE androidx/exifinterface exifinterface 1.3.6 aar
$GOOGLE androidx/concurrent concurrent-futures 1.2.0 jar
$CENTRAL com/google/guava guava 33.3.1-android jar
$CENTRAL com/google/guava failureaccess 1.0.2 jar
"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "$LIBS" | while read -r repo group artifact version type; do
  [ -n "${repo:-}" ] || continue
  out="libs/$artifact-$version.jar"
  [ -f "$out" ] && continue
  url="$repo/$group/$artifact/$version/$artifact-$version.$type"
  curl -fsSL -o "$tmp/file" "$url"
  want="$(curl -fsSL "$url.sha1" | cut -c1-40)"
  got="$(shasum -a 1 "$tmp/file" | cut -d' ' -f1)"
  [ "$want" = "$got" ] || { echo "Checksum mismatch for $url" >&2; exit 1; }
  if [ "$type" = aar ]; then
    mkdir -p "$tmp/aar" && rm -rf "$tmp/aar/"*
    unzip -q -o "$tmp/file" -d "$tmp/aar"
    mv "$tmp/aar/classes.jar" "$out"
  else
    mv "$tmp/file" "$out"
  fi
  echo "fetched $artifact $version"
done
