#!/bin/sh
set -eu

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 /absolute/path/to/credentials.txt <benchmark arguments...>" >&2
  exit 2
fi

CREDENTIAL_FILE=$1
shift

if [ ! -f "$CREDENTIAL_FILE" ]; then
  echo "Credential file does not exist." >&2
  exit 2
fi

read_field() {
  awk -v wanted="$1" '
    {
      sub(/^\xef\xbb\xbf/, "")
      gsub(/\r$/, "")
      prefix = wanted ":"
      if (index($0, prefix) == 1) {
        value = substr($0, length(prefix) + 1)
        sub(/^[[:space:]]+/, "", value)
        print value
        exit
      }
    }
  ' "$CREDENTIAL_FILE"
}

COGNODB_URI=$(read_field "Connection URI")
COGNODB_USERNAME=$(read_field "Username")
COGNODB_PASSWORD=$(read_field "Password")

if [ -z "$COGNODB_URI" ] || [ -z "$COGNODB_USERNAME" ] || [ -z "$COGNODB_PASSWORD" ]; then
  echo "The credential file is missing Connection URI, Username, or Password." >&2
  exit 2
fi

export COGNODB_URI COGNODB_USERNAME COGNODB_PASSWORD

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "$PROJECT_DIR/benchmark.sh" "$@"

