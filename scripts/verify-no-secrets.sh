#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CREDENTIAL_FILE=${1:-}
FOUND=0

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

check_value() {
  label=$1
  value=$2
  if [ -z "$value" ]; then
    return
  fi
  matches=$(grep -R -F -l \
    --exclude-dir=.git \
    --exclude-dir=target \
    --exclude-dir=.tools -- "$value" "$PROJECT_DIR" 2>/dev/null || true)
  if [ -n "$matches" ]; then
    echo "Secret scan failed: the supplied $label appears in:" >&2
    echo "$matches" >&2
    FOUND=1
  fi
}

if [ -n "$CREDENTIAL_FILE" ]; then
  if [ ! -f "$CREDENTIAL_FILE" ]; then
    echo "Credential file does not exist." >&2
    exit 2
  fi
  check_value "connection URI" "$(read_field "Connection URI")"
  check_value "instance ID" "$(read_field "Instance ID")"
  check_value "password" "$(read_field "Password")"
fi

if [ "$FOUND" -ne 0 ]; then
  exit 1
fi

echo "Secret scan passed. No supplied credential value was found in the project."
