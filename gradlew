#!/usr/bin/env sh
# SGA MDS - launcher compatible with Linux/macOS.
# It intentionally bootstraps Gradle 8.9 without requiring a checked-in binary wrapper JAR.
set -eu

VERSION="8.9"
SHA256="d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/mds-bootstrap"
INSTALL_DIR="$CACHE_ROOT/gradle-$VERSION"
ZIP_FILE="$CACHE_ROOT/gradle-$VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"

verify_hash() {
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$ZIP_FILE" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL="$(shasum -a 256 "$ZIP_FILE" | awk '{print $1}')"
  else
    echo "ERROR: no se encuentra sha256sum ni shasum para verificar Gradle." >&2
    return 1
  fi
  [ "$ACTUAL" = "$SHA256" ] || {
    echo "ERROR: checksum de Gradle incorrecto." >&2
    rm -f "$ZIP_FILE"
    return 1
  }
}

if [ ! -x "$INSTALL_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_ROOT"
  if [ ! -f "$ZIP_FILE" ]; then
    echo "Descargando Gradle $VERSION..."
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --retry 3 --output "$ZIP_FILE" "$URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "$URL"
    else
      echo "ERROR: instala curl o wget." >&2
      exit 1
    fi
  fi
  verify_hash
  TMP_DIR="$CACHE_ROOT/unpack-$VERSION-$$"
  rm -rf "$TMP_DIR"
  mkdir -p "$TMP_DIR"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP_FILE" -d "$TMP_DIR"
  else
    echo "ERROR: instala unzip." >&2
    exit 1
  fi
  rm -rf "$INSTALL_DIR"
  mv "$TMP_DIR/gradle-$VERSION" "$INSTALL_DIR"
  rm -rf "$TMP_DIR"
fi

exec "$INSTALL_DIR/bin/gradle" "$@"
