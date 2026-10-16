#!/usr/bin/env bash
# Fetch pinned third-party sources (libopus, BSD) into third_party/ and verify their SHA-256.
set -eu
cd "$(dirname "$0")"
mkdir -p third_party && cd third_party
fetch() {  # name url sha256 dir
  [ -d "$4" ] && return
  curl -sSL -o "$1" "$2"
  echo "$3  $1" | shasum -a 256 -c - >/dev/null || { echo "CHECKSUM MISMATCH for $1"; rm -f "$1"; exit 1; }
  tar xzf "$1" && rm "$1"
  echo "fetched $4"
}
fetch opus-1.5.2.tar.gz https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz \
  65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1 opus-1.5.2
