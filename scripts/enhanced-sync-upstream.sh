#!/usr/bin/env bash
# Merge the official Jellyfin Android TV client into this fork.
# Usage: scripts/enhanced-sync-upstream.sh [ref]   (default: upstream master; a tag such as v0.20.0 also works)
set -euo pipefail

UPSTREAM_URL=https://github.com/jellyfin/jellyfin-androidtv.git
REF=${1:-master}

cd "$(git rev-parse --show-toplevel)"

if [ -n "$(git status --porcelain)" ]; then
	echo "Working tree has uncommitted changes; commit them first." >&2
	exit 1
fi

if ! git remote get-url upstream >/dev/null 2>&1; then
	git remote add upstream "$UPSTREAM_URL"
fi
git fetch upstream --tags

if git rev-parse -q --verify "refs/tags/$REF" >/dev/null; then
	TARGET="$REF"
else
	TARGET="upstream/$REF"
fi

echo "Merging $TARGET into $(git branch --show-current)…"
if git merge --no-edit "$TARGET"; then
	echo "Merged. Build with ./gradlew assembleDebug and check the hooks still compile."
else
	echo
	echo "Merge conflicts. They will be in the hook spots listed in ENHANCED.md:"
	git diff --name-only --diff-filter=U
	echo "Keep upstream's code, re-add the hook line, then: git add <files> && git commit"
	exit 1
fi
