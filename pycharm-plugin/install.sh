#!/usr/bin/env bash
# Install the built plugin into the PyCharm (Remote Dev backend) plugins dir. Restart the IDE backend afterwards.
#
# Source: the newest build/distributions/codemap-pycharm-<version>.zip (./gradlew buildPlugin; BUILD_DIR=<dir> picks
# another Gradle output dir, e.g. build-foo for ./gradlew -Pcodemap.buildDir=build-foo). The target dir
# <plugins>/codemap-pycharm/ is replaced as a whole, and any other directory in <plugins> that holds a Code Map plugin
# jar (current id com.sushil.codemap or the old dev.codemap.pycharm) is removed, so exactly one copy is installed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGINS_DIR="${PYCHARM_PLUGINS_DIR:-$HOME/.local/share/JetBrains/PyCharm2025.3}"
NAME="codemap-pycharm"
DEST="$PLUGINS_DIR/$NAME"
PYTHON="${PYTHON:-$(command -v python3 || command -v python)}"

BUILD_DIR="${BUILD_DIR:-build}"
ZIP="$(ls -t "$HERE/$BUILD_DIR"/distributions/"$NAME"-*.zip 2>/dev/null | grep -v -- '-signed\.zip$' | head -n1 || true)"
if [ -z "$ZIP" ]; then
  echo "No plugin ZIP in $HERE/$BUILD_DIR/distributions. Build it first:  (cd $HERE && ./gradlew buildPlugin)" >&2
  exit 1
fi

mkdir -p "$PLUGINS_DIR"
STAGE="$(mktemp -d "$PLUGINS_DIR/.$NAME-install.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
"$PYTHON" -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$ZIP" "$STAGE"
[ -d "$STAGE/$NAME/lib" ] || { echo "unexpected ZIP layout (no $NAME/lib): $ZIP" >&2; exit 1; }

# Remove every installed copy (this dir and any other dir whose jar declares a Code Map plugin id).
"$PYTHON" -I - "$PLUGINS_DIR" "$NAME" <<'PY'
import os, re, shutil, sys, zipfile
plugins, name = sys.argv[1], sys.argv[2]
ids = re.compile(rb"<id>\s*(com\.sushil\.codemap|dev\.codemap\.pycharm)\s*</id>")
for d in sorted(os.listdir(plugins)):
    p = os.path.join(plugins, d)
    if d.startswith(".") or not os.path.isdir(p):
        continue
    hit = d == name
    lib = os.path.join(p, "lib")
    if not hit and os.path.isdir(lib):
        for j in os.listdir(lib):
            if j.endswith(".jar"):
                try:
                    with zipfile.ZipFile(os.path.join(lib, j)) as z:
                        hit = ids.search(z.read("META-INF/plugin.xml")) is not None
                except (KeyError, zipfile.BadZipFile, OSError):
                    pass
            if hit:
                break
    if hit:
        shutil.rmtree(p)
        print("removed", p)
PY
mv "$STAGE/$NAME" "$DEST"
echo "Installed $ZIP -> $DEST"
find "$DEST" -type f | sed 's/^/  /'
echo "Restart the PyCharm backend (File | Exit, then reconnect from JetBrains Gateway/Client) to load it."
