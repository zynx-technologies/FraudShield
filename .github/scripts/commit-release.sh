#!/usr/bin/env bash
# Changes one file on the "releases" branch, which holds release state that Argo CD reads:
#   environments/<env>/release.yaml          image tag            (app.yml)
#   environments/<env>/model.yaml            model versions       (model-release.yml)
#   environments/<env>/platform-<region>.yaml Terraform outputs   (infra.yml)
# Configuration stays on main and changes only through pull requests.
#
# Usage: commit-release.sh <path> <yq-expression> <commit message>
# Azure Pipelines: needs SYSTEM_ACCESSTOKEN, and the build service needs Contribute on the repository.
# GitHub Actions:  needs GITHUB_TOKEN with "contents: write".
set -euo pipefail

TARGET="$1"
EXPRESSION="$2"
MESSAGE="$3"
if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
  : "${GITHUB_TOKEN:?Set GITHUB_TOKEN in the environment of this step}"
  REPO="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}.git"
  AUTH_HEADER="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 -w0)"
  GIT_EMAIL="41898282+github-actions[bot]@users.noreply.github.com"
else
  : "${SYSTEM_ACCESSTOKEN:?Map System.AccessToken into the environment of this step}"
  REPO="${BUILD_REPOSITORY_URI:?}"
  AUTH_HEADER="AUTHORIZATION: bearer $SYSTEM_ACCESSTOKEN"
  GIT_EMAIL="pipelines@fraudshield.invalid"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git_auth() { git -c "http.extraheader=$AUTH_HEADER" "$@"; }

if ! git_auth clone --quiet --depth 1 --branch releases "$REPO" "$WORK" 2>/dev/null; then
  echo "Creating the releases branch."
  git_auth clone --quiet --depth 1 "$REPO" "$WORK"
  git -C "$WORK" checkout --quiet --orphan releases
  git -C "$WORK" rm -rf --quiet .
fi

cd "$WORK"
git config user.email "$GIT_EMAIL"
git config user.name "FraudShield pipelines"

for attempt in 1 2 3 4 5; do
  mkdir -p "$(dirname "$TARGET")"
  [[ -f "$TARGET" ]] || echo "{}" > "$TARGET"
  yq -i "$EXPRESSION" "$TARGET"
  git add "$TARGET"
  if git diff --cached --quiet; then
    echo "$TARGET is already up to date."
    exit 0
  fi
  git commit --quiet -m "$MESSAGE"
  if git_auth push --quiet origin HEAD:releases; then
    echo "Updated $TARGET on releases: $MESSAGE"
    exit 0
  fi
  echo "Another pipeline pushed first. Retrying ($attempt of 5)."
  git_auth fetch --quiet --depth 1 origin releases
  git reset --quiet --hard origin/releases
  sleep $((attempt * 3))
done

echo "Could not update $TARGET after 5 attempts." >&2
exit 1