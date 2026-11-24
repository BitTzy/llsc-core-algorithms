#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
if [ -n "${JAVA_HOME:-}" ]; then
    java_exe="$JAVA_HOME/bin/java"
else
    java_exe=java
fi
exec "$java_exe" -jar target/lending-ledger-desktop.jar
