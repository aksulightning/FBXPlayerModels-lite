#!/usr/bin/env bash
set -euo pipefail

: "${TAG_NAME:?TAG_NAME is required}"
: "${RELEASE_NAME:?RELEASE_NAME is required}"
: "${BRANCH_NAME:?BRANCH_NAME is required}"
: "${BUILD_PATH:?BUILD_PATH is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

shopt -s nullglob
artifacts=("$BUILD_PATH"/*)
if (( ${#artifacts[@]} == 0 )); then
  echo "::error::No nightly artifacts found in $BUILD_PATH"
  exit 1
fi
for artifact in "${artifacts[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    echo "::error::Nightly artifact is not a file: $artifact"
    exit 1
  fi
done

commit_sha=$(git rev-parse HEAD)
notes_file=$(mktemp "${RUNNER_TEMP:-/tmp}/fbx-nightly-notes.XXXXXX")
trap 'rm -f "$notes_file"' EXIT
printf 'Automatic nightly build.\n\nBranch: %s\nCommit: %s\n' \
  "$BRANCH_NAME" "$commit_sha" > "$notes_file"

retry() {
  local attempt status delay=10
  for (( attempt=1; attempt<=5; attempt++ )); do
    if "$@"; then
      return 0
    else
      status=$?
    fi
    if (( attempt == 5 )); then
      echo "::error::$1 failed after $attempt attempts (exit $status)."
      return "$status"
    fi
    echo "::warning::$1 failed (attempt $attempt/5); retrying in $delay seconds."
    sleep "$delay"
    delay=$(( delay * 2 ))
    if (( delay > 60 )); then
      delay=60
    fi
  done
}

verify_tag() {
  local remote_ref
  remote_ref=$(git ls-remote --exit-code origin "refs/tags/$TAG_NAME") || return
  if [[ "${remote_ref%%$'\t'*}" != "$commit_sha" ]]; then
    echo "::error::Remote tag $TAG_NAME does not point to the built commit $commit_sha."
    return 1
  fi
}

ensure_release() {
  local release_id
  # GraphQL includes drafts left by partially successful create requests.
  # Failed lookups must be retried before deciding whether creation is needed.
  release_id=$(gh api graphql \
    -f owner="${GITHUB_REPOSITORY%%/*}" -f name="${GITHUB_REPOSITORY#*/}" \
    -f tag="$TAG_NAME" -f query='
      query($owner: String!, $name: String!, $tag: String!) {
        repository(owner: $owner, name: $name) {
          release(tagName: $tag) { id }
        }
      }' --jq '.data.repository.release.id // empty') || return
  if [[ -z "$release_id" ]]; then
    gh release create "$TAG_NAME" --repo "$GITHUB_REPOSITORY" \
      --title "$RELEASE_NAME" --notes-file "$notes_file" \
      --draft --prerelease --latest=false --verify-tag || return
  fi
}

retry git push --force origin "$commit_sha:refs/tags/$TAG_NAME"
retry verify_tag
retry ensure_release
for artifact in "${artifacts[@]}"; do
  # Retry each file separately, retaining the other completed uploads.
  retry gh release upload "$TAG_NAME" "$artifact" \
    --repo "$GITHUB_REPOSITORY" --clobber
done
retry gh release edit "$TAG_NAME" --repo "$GITHUB_REPOSITORY" \
  --title "$RELEASE_NAME" --notes-file "$notes_file" \
  --draft=false --prerelease --latest=false --verify-tag
