#!/usr/bin/env bash
# Linux build used locally and by GitHub Actions. Requires JDK 21 and an Android
# SDK whose licenses have already been accepted by the person running the build.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory.}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-37.0}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-37.0.0}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-30.0.16248370}"
ANDROID_BUILD_TYPE="${ANDROID_BUILD_TYPE:-debug}"
case "$ANDROID_BUILD_TYPE" in
    debug) build_variant=Debug ;;
    release)
        : "${ZHUZHALKA_KEYSTORE_PATH:?Release builds require the permanent keystore.}"
        : "${ZHUZHALKA_KEYSTORE_PASSWORD:?Release builds require the keystore password.}"
        build_variant=Release ;;
    *) printf '%s\n' 'ANDROID_BUILD_TYPE must be debug or release.' >&2; exit 1 ;;
esac
export NDK_HOME="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"

XRAY_TAG=v26.10.21
XRAY_COMMIT=76a6e2172210613fcb4aa32b6de55bcd9452ce43
XRAY_SHA256=e56ec3b06485fe7b37af26948673951edf719c50d6bbc618b8539045952adde4
HEV_COMMIT=c5584feca8f6df70ee53eb2879e034b849a3d15a

if [[ "$(uname -s)" != Linux ]]; then
    printf '%s\n' 'This helper requires Linux; use the Windows instructions for a Windows build.' >&2
    exit 1
fi
for tool in java git curl sha256sum; do
    command -v "$tool" >/dev/null || { printf 'Missing required tool: %s\n' "$tool" >&2; exit 1; }
done
java_version="$(java -version 2>&1)"
if [[ "$java_version" != *'version "21.'* ]]; then
    printf '%s\n' 'JDK 21 is required. Set JAVA_HOME and PATH to your JDK 21 installation.' >&2
    exit 1
fi

required_files=(
    "$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar" \
    "$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/aapt2" \
    "$NDK_HOME/ndk-build"
)
missing_sdk_package=false
for required_file in "${required_files[@]}"; do
    [[ -f "$required_file" ]] || missing_sdk_package=true
done
if [[ "$missing_sdk_package" == true ]]; then
    sdkmanager="$(command -v sdkmanager || true)"
    if [[ -z "$sdkmanager" ]]; then
        sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    fi
    if [[ ! -x "$sdkmanager" ]]; then
        printf '%s\n' 'Install Android SDK command-line tools and add sdkmanager to PATH.' >&2
        exit 1
    fi
    # A missing license must fail instead of being accepted automatically.
    "$sdkmanager" --sdk_root="$ANDROID_HOME" --channel=0 --install \
        "platforms;$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS" \
        "ndk;$ANDROID_NDK_VERSION" </dev/null
fi
for required_file in "${required_files[@]}"; do
    if [[ ! -f "$required_file" ]]; then
        printf 'Missing SDK component: %s\nReview SDK licenses and install the requested packages before retrying.\n' "$required_file" >&2
        exit 1
    fi
done

git submodule update --init --recursive
verify_revision() {
    local module="$1" expected="$2" recorded checked_out
    recorded="$(git ls-tree HEAD "$module" | awk '{print $3}')"
    checked_out="$(git -C "$module" rev-parse HEAD)"
    if [[ "$recorded" != "$expected" || "$checked_out" != "$expected" ]]; then
        printf 'Unexpected %s revision; review native source pins before building.\n' "$module" >&2
        exit 1
    fi
}
verify_revision AndroidLibXrayLite "$XRAY_COMMIT"
verify_revision hev-socks5-tunnel "$HEV_COMMIT"

mkdir -p V2rayNG/app/libs
aar_file="$(mktemp "${TMPDIR:-/tmp}/zhuzhalka-xray.XXXXXX.aar")"
trap 'rm -f -- "$aar_file"' EXIT
curl --fail --location --proto '=https' --tlsv1.2 \
    --connect-timeout 20 --max-time 300 --retry 3 \
    --output "$aar_file" \
    "https://github.com/2dust/AndroidLibXrayLite/releases/download/$XRAY_TAG/libv2ray.aar"
printf '%s  %s\n' "$XRAY_SHA256" "$aar_file" | sha256sum --check --status
install -m 644 "$aar_file" V2rayNG/app/libs/libv2ray.aar

# Reproduce the upstream JNI and standalone HEV builds for every supported ABI.
bash compile-hevtun.sh
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    mkdir -p "V2rayNG/app/libs/$abi"
    for library in libhev-socks5-tunnel.so libhevsockstun.so; do
        test -s "libs/$abi/$library"
        install -m 644 "libs/$abi/$library" "V2rayNG/app/libs/$abi/$library"
    done
done

cd V2rayNG
bash gradlew --no-daemon --console=plain --stacktrace \
    :app:testPlaystoreDebugUnitTest \
    :app:testFdroidDebugUnitTest \
    :app:compilePlaystoreDebugKotlin \
    :app:compileFdroidDebugKotlin \
    ":app:assemblePlaystore$build_variant" \
    ":app:assembleFdroid$build_variant"

cd app/build/outputs/apk
find . -type f -name '*.apk' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
printf '\nAPKs and checksums: %s\n' "$PWD"
cat SHA256SUMS
