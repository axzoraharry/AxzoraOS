#!/usr/bin/env bash
# Build AxzoraOS on this aarch64 Android device (Termux environment).
# Sources the on-device JDK 17, native aapt2, and locally installed SDK, then
# hands off to the project's Gradle wrapper. Run from the project root:
#
#   bash build-on-device.sh :app:assembleDebug
#
set -euo pipefail
PREFIX="${PREFIX:-/data/data/com.dsharnessmobile.shell/files/usr}"
export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$PREFIX/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export TMPDIR="${TMPDIR:-$HOME/tmp}"
mkdir -p "$TMPDIR"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
cd "$(dirname "$0")"
echo "JAVA_HOME=$JAVA_HOME  aapt2=$(command -v aapt2)"
echo "==> gradlew $*"
exec bash gradlew "$@"
