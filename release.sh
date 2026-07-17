#!/usr/bin/env bash
# Cut a release, npm-version style:
#   ./release.sh 0.3.0
# Bumps appVersion in gradle.properties, creates a commit titled "0.3.0" and the
# tag v0.3.0, then pushes main + tag. The tag triggers the Release APK workflow.
#
# This version-bump commit is the one sanctioned direct-to-main commit (same
# convention as `npm version` in the JewelBox-Music-Library repo): it makes each
# release visible in the commit history.
set -euo pipefail
cd "$(dirname "$0")"

V="${1:?usage: ./release.sh X.Y.Z}"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "❌ Version invalide : « $V » (attendu X.Y.Z)"; exit 1; }

[[ "$(git branch --show-current)" == "main" ]] || { echo "❌ À lancer depuis main"; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "❌ Working tree non propre — committe ou stash d'abord"; exit 1; }
git pull --ff-only

if git rev-parse "v$V" >/dev/null 2>&1; then
  echo "❌ Le tag v$V existe déjà"; exit 1
fi

# Bump appVersion (read by app/build.gradle.kts as the local versionName; the CI
# overrides it from the tag anyway, this keeps local builds coherent).
if grep -q '^appVersion=' gradle.properties; then
  sed -i "s/^appVersion=.*/appVersion=$V/" gradle.properties
else
  printf '\n# App version, bumped by release.sh (CI reads the git tag instead)\nappVersion=%s\n' "$V" >> gradle.properties
fi

git add gradle.properties
git commit -m "$V"
git tag "v$V"
git push origin main "v$V"

echo "✅ Release v$V poussée — GitHub Actions construit et publie l'APK signé."
