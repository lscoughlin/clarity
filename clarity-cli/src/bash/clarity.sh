#!/usr/bin/env bash
# clarity launcher: runs the clarity-cli fat jar sitting next to this
# script, passing all arguments through. Resolves symlinks so it
# works when installed via a link on PATH.
set -u

SCRIPT_PATH="$0"
while [ -L "$SCRIPT_PATH" ]; do
    LINK="$(readlink "$SCRIPT_PATH")"
    case "$LINK" in
        /*) SCRIPT_PATH="$LINK" ;;
        *) SCRIPT_PATH="$(dirname "$SCRIPT_PATH")/$LINK" ;;
    esac
done
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"

JAR=""
for candidate in "$SCRIPT_DIR"/clarity-cli*.jar; do
    if [ -f "$candidate" ]; then
        JAR="$candidate"
        break
    fi
done
if [ -z "$JAR" ]; then
    echo "clarity: no clarity-cli*.jar found in $SCRIPT_DIR" >&2
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="java"
else
    echo "clarity: no java runtime found (set JAVA_HOME)" >&2
    exit 1
fi

exec "$JAVA" -jar "$JAR" "$@"
