#!/usr/bin/env bash
# Recreate the benchmark corpora as read-only-source COPIES (never analyse/open the originals:
# opening a project in the IDE writes .idea/ into it).
#
#   bench/corpora.sh [DEST]      DEST defaults to $CODEMAP_CORPORA or the session scratchpad below.
#
# Layout produced (the project ROOT handed to both analyzers is DEST/<name>):
#   DEST/gis_api/...                 <- contents of the gis_api repo (so modules are gis_api.*, database.*, tests.*)
#   DEST/fastapi/fastapi/...         <- site-packages/fastapi    (modules fastapi.*)
#   DEST/celery/celery/...           <- site-packages/celery     (modules celery.*)
#   DEST/sqlalchemy/sqlalchemy/...   <- site-packages/sqlalchemy (modules sqlalchemy.*)
# rsync --delete makes repeated runs reproducible (same file set as the sources at that moment).
set -euo pipefail

DEFAULT_DEST=/tmp/claude-1000/-home-sushil-PycharmProjects-gis-api/679583d0-1cbe-4a24-b9b3-6a164c92c301/scratchpad/psi/corpora
DEST="${1:-${CODEMAP_CORPORA:-$DEFAULT_DEST}}"
GIS_API_SRC="${GIS_API_SRC:-/home/sushil/PycharmProjects/gis_api}"
SITE="${SITE_PACKAGES:-/home/sushil/anaconda3/envs/gis_api/lib/python3.13/site-packages}"

RSYNC=(rsync -a --delete --exclude .git --exclude .idea --exclude __pycache__ --exclude '*.pyc')

mkdir -p "$DEST"
echo "gis_api   <- $GIS_API_SRC"
"${RSYNC[@]}" "$GIS_API_SRC/" "$DEST/gis_api/"
for pkg in fastapi celery sqlalchemy; do
    echo "$pkg <- $SITE/$pkg"
    mkdir -p "$DEST/$pkg"
    "${RSYNC[@]}" "$SITE/$pkg/" "$DEST/$pkg/$pkg/"
done
for name in gis_api fastapi celery sqlalchemy; do
    printf '%-11s %5d .py files\n' "$name" "$(find "$DEST/$name" -name '*.py' | wc -l)"
done
