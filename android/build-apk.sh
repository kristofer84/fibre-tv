#!/bin/sh
#
# Runs INSIDE the build container; invoked by build.sh. Not meant to run on the
# host.
#
# Expects /toolchain (read-only, pinned archives) and the project at /work.
# Produces app/build/iptv-tv.apk.
#
set -eu

APP=android/app
# Absolute: several steps cd into subdirectories of it, which would otherwise
# re-base a relative path (build.sh runs with the repo as the working directory).
BUILD=$PWD/$APP/build

# Both ABIs, because the Chromecast with Google TV (4K, "sabrina") has a 32-bit
# userspace: ro.product.cpu.abilist is "armeabi-v7a,armeabi" and abilist64 is
# empty, so an arm64-only apk is refused with INSTALL_FAILED_NO_MATCHING_ABIS.
# arm64-v8a is kept as well to cover Google TV devices that are 64-bit.
ABIS="armeabi-v7a arm64-v8a"

: "${PLATFORM_ZIP:?build.sh must pass PLATFORM_ZIP}"
: "${R8_JAR:?build.sh must pass R8_JAR}"
: "${LIBVLC_AAR:?build.sh must pass LIBVLC_AAR}"

step() { printf '\n== %s\n' "$1"; }

rm -rf "$BUILD"
mkdir -p "$BUILD/tc" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/assets"

# Stage the channel list from the repository root: the one file in this project
# that is also documentation, so it must not be duplicated here.
cp channels.m3u8 "$BUILD/assets/channels.m3u8"

# Licence texts travel inside the apk. libVLC is LGPL-2.1-or-later and its aar
# ships no licence file, so distributing an apk means distributing the text.
mkdir -p "$BUILD/assets/licenses"
cp android/THIRD-PARTY.md android/LICENSE "$BUILD/assets/licenses/"
cp android/licenses/*.txt "$BUILD/assets/licenses/"

step "unpack toolchain"
unzip -qo "/toolchain/$PLATFORM_ZIP" 'android-*/android.jar' -d "$BUILD/tc"

# Only the ABIs being shipped: the aar also carries x86 and x86_64, about 85 MB
# of native libraries this apk has no use for. $patterns is deliberately unquoted
# so the shell splits it into one unzip pattern per ABI.
patterns=
for abi in $ABIS; do
    mkdir -p "$BUILD/lib/$abi"
    patterns="$patterns jni/$abi/*"
done
unzip -qo "/toolchain/$LIBVLC_AAR" 'classes.jar' $patterns -d "$BUILD/vlc"
for abi in $ABIS; do
    mv "$BUILD/vlc/jni/$abi"/*.so "$BUILD/lib/$abi/"
done

ANDROID_JAR=$(echo "$BUILD"/tc/android-*/android.jar)
# R8's jar is already a jar; d8 is run straight out of it.
D8_JAR=/toolchain/$R8_JAR
[ -s "$D8_JAR" ] || { echo "missing $D8_JAR" >&2; exit 1; }
VLC_JAR="$BUILD/vlc/classes.jar"
printf '  android.jar  %s\n  d8 (r8)      %s\n  libvlc       %s\n' \
    "$ANDROID_JAR" "$D8_JAR" "$VLC_JAR"

step "resources and manifest (aapt, v1)"
aapt package -f -m \
    -M "$APP/AndroidManifest.xml" \
    -S "$APP/res" \
    -A "$BUILD/assets" \
    -I "$ANDROID_JAR" \
    -J "$BUILD/gen" \
    -F "$BUILD/app.apk"

step "compile (javac, against android.jar + libvlc)"
javac -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$VLC_JAR" \
    -encoding UTF-8 \
    -d "$BUILD/classes" \
    $(find "$APP/src" -name '*.java')

step "dex (d8)"
# libVLC's classes.jar is an *input*, not --classpath: not passing it would tell d8
# the types live elsewhere and leave them out of the apk entirely, which shows up
# at runtime as ClassNotFoundException for the activity (its interface
# IVLCVout$Callback cannot be resolved, so loading the class fails). The aar's
# classes are the Java half of libVLC; the .so files are the other half.
java -cp "$D8_JAR" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" \
    --min-api 21 \
    --output "$BUILD/dex" \
    "$VLC_JAR" \
    $(find "$BUILD/classes" -name '*.class')

step "package"
cp "$BUILD/app.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unsigned.apk" classes.dex)
(cd "$BUILD"     && zip -q unsigned.apk lib/*/*.so)

step "align"
zipalign -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

step "sign"
KEY_ALIAS=${KEY_ALIAS:-iptv}
KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD:-android}
KEY_PASSWORD=${KEY_PASSWORD:-android}
# build.sh mounts the repo root as the working directory, so the keystore path has
# to be spelled out: a bare keys/ would land in the repo root, not android/keys.
KEYSTORE=$PWD/android/keys/iptv-tv.jks
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -s "$KEYSTORE" ]; then
    # CI without the ANDROID_KEYSTORE_BASE64 secret lands here. The apk still
    # installs, but it cannot upgrade an install signed with a different key -
    # every run would first need an uninstall.
    printf '  no keystore given: generating a throwaway one\n'
    printf '  set ANDROID_KEYSTORE_BASE64 to sign with a stable key\n'
    keytool -genkeypair -keystore "$KEYSTORE" -storetype pkcs12 \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" \
        -dname "CN=the operator IPTV TV app, O=local, C=SE"
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" --key-pass "pass:$KEY_PASSWORD" \
    --out "$BUILD/iptv-tv.apk" "$BUILD/aligned.apk"

step "verify"
apksigner verify --print-certs "$BUILD/iptv-tv.apk"
aapt dump badging "$BUILD/iptv-tv.apk" | grep -E "^package|^application|^launchable|^sdkVersion|^targetSdkVersion|^native-code|^uses-permission" | head -12
printf 'native libs: '; unzip -l "$BUILD/iptv-tv.apk" | awk '$4 ~ /^lib\// {split($4,a,"/"); print a[2]}' | sort -u | tr '\n' ' '; echo
printf 'licences in apk: '; unzip -l "$BUILD/iptv-tv.apk" | awk '$4 ~ /^assets\/licenses\// {print $4}' | sed 's#assets/licenses/##' | tr '\n' ' '; echo
# Guard the mistake that cost the first device run: libVLC's Java API must be
# inside classes.dex, or the activity cannot even load. grep -a, not strings:
# strings silently misses some descriptors (it reported a false MISSING for one).
printf 'dex contains: '; for c in net/xcds/iptv/TvActivity org/videolan/libvlc/MediaPlayer org/videolan/libvlc/interfaces/IVLCVout; do
    unzip -p "$BUILD/iptv-tv.apk" classes.dex | grep -aq "$c" && printf '%s ' "$c" || printf 'MISSING(%s) ' "$c"
done; echo
printf '\n%s (%s bytes)\n' "$BUILD/iptv-tv.apk" "$(stat -c%s "$BUILD/iptv-tv.apk")"
