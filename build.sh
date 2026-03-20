#!/bin/bash

export JAVA_HOME=/usr/local/opt/openjdk@17
export ANDROID_HOME=/Users/eness/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/latest/bin:$ANDROID_HOME/platform-tools:$PATH

echo "=== 环境检查 ==="
java -version
echo "ANDROID_HOME: $ANDROID_HOME"

# 开始构建
echo ""
echo "=== 开始构建AutoX MCP版本 ==="
echo ""

# 先构建JS模块
echo ">>> 第1步：构建JS模块..."
./gradlew autojs:buildJsModule

# 构建模板APP
echo ""
echo ">>> 第2步：构建模板APP..."
./gradlew app:buildDebugTemplateApp

# 组装V7 Debug版本
echo ""
echo ">>> 第3步：组装V7 Debug APK..."
./gradlew app:assembleV7Debug

echo ""
echo "=== 构建完成 ==="
echo "APK位置: app/build/outputs/apk/v7/debug/"
ls -la app/build/outputs/apk/v7/debug/*.apk 2>/dev/null || echo "未找到APK文件"
