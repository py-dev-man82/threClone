#!/usr/bin/env bash

set -euo pipefail

GREEN="\033[0;32m"
RED="\033[0;31m"
RESET="\033[0m"

function log() {
    echo -en "$1"
    echo -n "$2 $3"
    echo -e "$RESET"
}

function log_major() { log "$GREEN" "==>" "$1"; }
function log_minor() { log "$GREEN" "--> " "$1"; }
function log_error() { log "$RED" "!!!" "Error: $1"; }

function fail() {
    log_error "$1"
    exit 1
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROTO_SRC="$SCRIPT_DIR/protocol/src"
OUT_DIR="$SCRIPT_DIR/build/generated/source/proto/main"
JAVA_OUT="$OUT_DIR/java"
KOTLIN_OUT="$OUT_DIR/kotlin"

mkdir -p "$JAVA_OUT"
mkdir -p "$KOTLIN_OUT"

log_major "Compiling protobuf"
for file in "$PROTO_SRC"/*.proto; do
    if [[ -f "$file" ]]; then
        log_minor "Building $file..."
        # ...existing code...
                protoc \
                  --proto_path="$PROTO_SRC" \
                  --java_out=lite:"$JAVA_OUT" \
                  --experimental_allow_proto3_optional \
                  -I="$PROTO_SRC" \
                  "$file"
        # ...existing code...
        log_minor "  OK"
    fi
done