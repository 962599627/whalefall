#!/bin/sh
# 在 Linux/ARM 上构建「鲸落」APK 的入口脚本（不设置预编译变量时等同上游构建）。
# 用法：编辑下面的路径后执行 sh scripts/build_local_phone.sh
set -e
cd "$(dirname "$0")/.."

: "${MARCOMOVIE_JDK:=/usr/lib/jvm/java-17-openjdk-arm64}"
: "${MARCOMOVIE_BUILD_TOOLS:=/root/workspace/toolchain/sdk}"
: "${MARCOMOVIE_ANDROID_JAR:=/tmp/aosp/platform/android-35/android.jar}"
: "${MARCOMOVIE_PREBUILT_CATPAW_DIR:=/root/workspace/native-prebuilt/catpaw}"
: "${MARCOMOVIE_PREBUILT_DEADLINE_DIR:=/root/workspace/native-prebuilt/deadline}"
export MARCOMOVIE_JDK MARCOMOVIE_BUILD_TOOLS MARCOMOVIE_ANDROID_JAR \
       MARCOMOVIE_PREBUILT_CATPAW_DIR MARCOMOVIE_PREBUILT_DEADLINE_DIR

exec python3 scripts/build_android.py "$@"
