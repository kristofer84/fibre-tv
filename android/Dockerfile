# Build image for the the operator IPTV Android TV app.
#
# The point of this image is that the Android build needs exactly two tools that
# are shipped as x86_64-only binaries, and this avoids both:
#
#   aapt2     replaced by aapt v1, which Debian builds natively for aarch64
#   zipalign  same (build-tools' zipalign is x86_64; Debian's is not)
#
# Everything else is architecture-independent: javac/keytool/apksigner are Java,
# and d8 comes from R8 on Google's Maven, which is also a plain jar. So the same
# image builds on arm64 (this Pi) and on x86_64.
#
# Toolchain archives are NOT baked in; they are mounted read-only at /toolchain
# and extracted into the build directory by build-apk.sh. See build.sh.

FROM debian:trixie-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless \
        aapt \
        apksigner \
        zipalign \
        unzip \
        zip \
        ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# uid 1000 so build outputs land in the mount owned by the caller, not root.
# The image is only ever used to build, so a fixed uid is enough.
WORKDIR /work
