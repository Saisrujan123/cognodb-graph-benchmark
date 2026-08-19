#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PROXY_URL=${HTTPS_PROXY:-${https_proxy:-}}

if [ -z "$PROXY_URL" ]; then
  exec "$PROJECT_DIR/mvnw" "$@"
fi

proxy_address=${PROXY_URL#*://}
proxy_hostport=${proxy_address##*@}
proxy_host=${proxy_hostport%%:*}
proxy_port=${proxy_hostport##*:}

case "$proxy_host" in
  *[!A-Za-z0-9.-]*)
    echo "HTTPS proxy host contains unsupported characters; configure Maven settings manually." >&2
    exit 2
    ;;
esac
case "$proxy_port" in
  ''|*[!0-9]*)
    echo "HTTPS proxy port is missing or invalid; configure Maven settings manually." >&2
    exit 2
    ;;
esac

settings_file=$(mktemp "${TMPDIR:-/tmp}/graph-benchmark-maven-settings.XXXXXX")
trap 'rm -f "$settings_file"' EXIT HUP INT TERM
chmod 600 "$settings_file"
{
  printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"'
  printf '%s\n' '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"'
  printf '%s\n' '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">'
  printf '%s\n' '  <proxies>'
  printf '%s\n' '    <proxy>'
  printf '%s\n' '      <id>environment-https-proxy</id>'
  printf '%s\n' '      <active>true</active>'
  printf '%s\n' '      <protocol>http</protocol>'
  printf '      <host>%s</host>\n' "$proxy_host"
  printf '      <port>%s</port>\n' "$proxy_port"
  printf '%s\n' '    </proxy>'
  printf '%s\n' '  </proxies>'
  printf '%s\n' '</settings>'
} > "$settings_file"

"$PROJECT_DIR/mvnw" --settings "$settings_file" "$@"

