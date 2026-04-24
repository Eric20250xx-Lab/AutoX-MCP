#!/bin/bash

set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/usr/local/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/eness/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "=== 环境检查 ==="
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
command -v java >/dev/null || { echo "未找到 java"; exit 1; }
command -v node >/dev/null || { echo "未找到 node，请先安装 Node.js 20+"; exit 1; }
java -version
node -v

echo ""
echo "=== 开始构建 AutoX MCP 版本 ==="
echo ""

echo ">>> 第1步：构建 JS 模块..."
./gradlew autojs:buildJsModule

echo ""
echo ">>> 第2步：构建 Debug 模板..."
./gradlew app:buildDebugTemplateApp

echo ""
echo ">>> 第3步：构建 Debug APK..."
./gradlew app:assembleV7Debug

echo ""
echo "=== 构建完成 ==="
echo "Debug APK:"
find app/build/outputs/apk -type f -path "*v7*debug*.apk" | sort || true
