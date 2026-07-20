#!/usr/bin/env sh
set -eu

GRADLE_VERSION=8.11.1
DIST_NAME="gradle-${GRADLE_VERSION}-bin.zip"
DIST_URL="https://services.gradle.org/distributions/${DIST_NAME}"
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/diaryflip-wrapper"
ZIP_PATH="$CACHE_DIR/$DIST_NAME"
GRADLE_HOME="$CACHE_DIR/gradle-${GRADLE_VERSION}"

mkdir -p "$CACHE_DIR"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  if [ ! -f "$ZIP_PATH" ]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$DIST_URL" -o "$ZIP_PATH"
    elif command -v wget >/dev/null 2>&1; then
      wget "$DIST_URL" -O "$ZIP_PATH"
    else
      echo "DiaryFlip needs curl or wget for the first Gradle run." >&2
      exit 1
    fi
  fi
  rm -rf "$GRADLE_HOME"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP_PATH" -d "$CACHE_DIR"
  else
    echo "DiaryFlip needs the unzip command for the first Gradle run." >&2
    exit 1
  fi
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
