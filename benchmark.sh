#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$PROJECT_DIR/target/graph-cloud-benchmark.jar"

NEEDS_BUILD=0
if [ ! -f "$JAR" ]; then
  NEEDS_BUILD=1
elif find "$PROJECT_DIR/src" "$PROJECT_DIR/pom.xml" -type f -newer "$JAR" -print -quit | grep -q .; then
  NEEDS_BUILD=1
fi

if [ "$NEEDS_BUILD" -eq 1 ]; then
  "$PROJECT_DIR/scripts/maven.sh" -f "$PROJECT_DIR/pom.xml" -q clean verify
fi

exec java -jar "$JAR" --project-root "$PROJECT_DIR" "$@"
