#!/usr/bin/env bash
set -euo pipefail

TAG="${1:?release tag is required}"
TAG_COMMIT="$(git rev-parse --verify "refs/tags/$TAG^{commit}")"
BUILD_COMMIT="$(git rev-parse --verify HEAD)"
if [ "$TAG_COMMIT" != "$BUILD_COMMIT" ]; then
  echo "::error::Refusing to publish $BUILD_COMMIT as $TAG ($TAG_COMMIT)" >&2
  exit 1
fi
echo "Release source verified: $TAG = $BUILD_COMMIT"
