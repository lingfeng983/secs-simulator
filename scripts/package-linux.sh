#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
if [ -z "$VERSION" ]; then
    echo "cannot read version from pom.xml" >&2
    exit 1
fi

if [ ! -f frontend/dist/index.html ]; then
    npm --prefix frontend ci
    npm --prefix frontend run build
fi

mvn -B -q -DskipTests package

JAVA_HOME=${JAVA_HOME:?set JAVA_HOME to JDK 25}
JLINK="$JAVA_HOME/bin/jlink"
JDEPS="$JAVA_HOME/bin/jdeps"
[ -x "$JLINK" ] || { echo "missing jlink: $JLINK" >&2; exit 1; }
[ -x "$JDEPS" ] || { echo "missing jdeps: $JDEPS" >&2; exit 1; }

STAGE="$ROOT/package/secs-sim-$VERSION-linux-x64"
rm -rf "$STAGE"
mkdir -p "$STAGE/lib"
cp "$ROOT"/secs-*/target/*.jar "$STAGE/lib/"
rm -f "$STAGE/lib"/secs-dist-*.jar

DEPS=$("$JDEPS" --multi-release 25 --ignore-missing-deps --print-module-deps "$STAGE"/lib/*.jar)
"$JLINK" --add-modules "$DEPS" --strip-debug --no-header-files --no-man-pages \
    --compress=zip-6 --output "$STAGE/runtime"

cp -r frontend/dist "$STAGE/web"
cp packaging/linux/start-server.sh "$STAGE/"
cp packaging/linux/demo.json "$STAGE/"
cp packaging/systemd/secs-sim.service "$STAGE/"
cp packaging/systemd/secs-sim.env.example "$STAGE/"
cp packaging/systemd/README.md "$STAGE/README-systemd.md"
cp docs/schema-v5.md "$STAGE/docs-schema-v5.md"
chmod +x "$STAGE/start-server.sh"

TARBALL="$ROOT/package/secs-sim-$VERSION-linux-x64.tar.gz"
rm -f "$TARBALL"
tar -C "$ROOT/package" -czf "$TARBALL" "$(basename "$STAGE")"

echo "package: $STAGE"
echo "tarball: $TARBALL ($(du -h "$TARBALL" | cut -f1))"
