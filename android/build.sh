#!/bin/sh
#
# Build the the operator IPTV Android TV app.
#
#   ./build.sh          -> app/build/iptv-tv.apk
#   ./build.sh clean    -> throw away build output and toolchain archives
#
# The build itself runs in a container (see Dockerfile); this script only fetches
# the pinned toolchain into toolchain/ and drives docker. Nothing is installed on
# the host and nothing is compiled there.
#
set -eu
cd "$(dirname "$0")"

IMAGE=ownit-iptv-android-build
CACHE=toolchain

# Pinned. Changing a version here means changing nothing else - build-apk.sh only
# knows the stable names (/toolchain/android.jar, /toolchain/d8.jar, ...).
# API 34, not 35. aapt v1 here is Debian's, built from Android 14 sources, and it
# cannot read the compact resource entries API 35's android.jar uses: it reports
# "Entry offset at index N points outside the Type's boundaries" for every entry
# and then fails to resolve every android: attribute in the manifest. API 34 links
# cleanly, and matches targetSdkVersion below in app/AndroidManifest.xml.
PLATFORM_ZIP=platform-34-ext12_r01.zip    # -> android.jar (compile against API 34)
LIBVLC_AAR=libvlc-all-3.6.5.aar           # -> classes.jar + jni/arm64-v8a/*.so

# d8 comes from R8 on Google's Maven rather than from build-tools' lib/d8.jar.
# build-tools 34's d8 is R8 8.2.2, which predates JDK 21 and dies on *any* class
# file with a null-name NPE - Debian trixie has no JDK 17 to fall back to. R8 9.x
# is a plain jar like d8.jar, so this stays architecture-independent. Skipping
# build-tools also drops 61 MB of download for tools this build cannot use
# (aapt2 and zipalign there are x86-64).
R8_VERSION=9.5.23
R8_JAR="r8-$R8_VERSION.jar"

if [ "${1:-}" = clean ]; then
    rm -rf "$CACHE" app/build
    echo "removed $CACHE and app/build"
    exit 0
fi

fetch() {   # fetch <filename> <url>
    if [ -s "$CACHE/$1" ]; then
        printf '  cached    %s\n' "$1"
        return
    fi
    printf '  fetching  %s\n' "$1"
    mkdir -p "$CACHE"
    curl -fsSL --retry 3 -o "$CACHE/$1.part" "$2"
    mv "$CACHE/$1.part" "$CACHE/$1"
}

echo "toolchain"
fetch "$PLATFORM_ZIP" \
      "https://dl.google.com/android/repository/$PLATFORM_ZIP"
fetch "$R8_JAR" \
      "https://dl.google.com/android/maven2/com/android/tools/r8/$R8_VERSION/$R8_JAR"
fetch "$LIBVLC_AAR" \
      "https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/3.6.5/$LIBVLC_AAR"

mkdir -p keys app/build

# Sign with a stable key when one is handed in. Locally keys/iptv-tv.jks survives
# from the first build; in CI it will not exist, and without this every CI run
# would produce an apk signed by a different key - which installs, but cannot
# upgrade an existing install.
#
#   base64 -w0 android/keys/iptv-tv.jks     -> ANDROID_KEYSTORE_BASE64 secret
#
if [ -n "${ANDROID_KEYSTORE_BASE64:-}" ] && [ ! -s keys/iptv-tv.jks ]; then
    printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > keys/iptv-tv.jks
    echo "keystore: restored from ANDROID_KEYSTORE_BASE64"
fi

echo "image"
docker build -q -t "$IMAGE" . >/dev/null

echo "build"
# The whole repo is mounted, not just android/: build-apk.sh stages the channel
# list from ../channels.m3u8 inside the container, so that file stays the single
# source of truth and nothing is generated into the working tree.
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e PLATFORM_ZIP="$PLATFORM_ZIP" \
    -e R8_JAR="$R8_JAR" \
    -e LIBVLC_AAR="$LIBVLC_AAR" \
    -e KEY_ALIAS="${KEY_ALIAS:-}" \
    -e KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}" \
    -e KEY_PASSWORD="${KEY_PASSWORD:-}" \
    -v "$PWD/..:/work" \
    -v "$PWD/$CACHE:/toolchain:ro" \
    -w /work \
    "$IMAGE" ./android/build-apk.sh

echo
ls -l app/build/iptv-tv.apk
