#!/usr/bin/env bash
# pi 工作台 一键构建：aapt → javac → d8 → aapt 打包 → apksigner
# 兼容两种环境：Termux 本机（工具在 PATH）/ GitHub CI（ANDROID_HOME）
set -e
cd "$(dirname "$0")"

if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
  AAPT="$BT/aapt"
  APKSIGNER="$BT/apksigner"
  D8="$BT/d8"
  AJ=$(ls "$ANDROID_HOME"/platforms/*/android.jar | sort -V | tail -1)
else
  AAPT=aapt; APKSIGNER=apksigner; D8=d8
  AJ="${ANDROID_JAR:-$HOME/PiBridge/android.jar}"
fi
echo "== 工具: aapt=$(command -v "$AAPT" 2>/dev/null || echo "$AAPT") AJ=$AJ"

rm -rf gen obj build
mkdir -p gen obj build

# 1) 资源编译 + R.java
"$AAPT" package -f -m -J gen -M AndroidManifest.xml -S res -I "$AJ"

# 2) Java 编译（javac 8 语法，d8 负责脱糖）
SHERPA_JAR="libs/aar-extract/classes.jar"
JAVAC_CP="$AJ"
[ -f "$SHERPA_JAR" ] && JAVAC_CP="$AJ:$SHERPA_JAR"
javac -source 8 -target 8 -nowarn -Xlint:-options -cp "$JAVAC_CP" -d obj $(find src gen -name "*.java") || { echo "❌ 编译失败，中止打包"; exit 1; }

# 3) dex（含 sherpa-onnx AAR 类 + kotlin-stdlib）
"$D8" --min-api 24 --release --lib "$AJ" --output build $(find obj -name "*.class") "$SHERPA_JAR" libs/kotlin-stdlib.jar

# 4) 打包 + 塞 dex（--rename-manifest-package：应用包名 com.pihost（10 字符，与 bootstrap 路径字节兼容），代码包不变）
"$AAPT" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" --rename-manifest-package com.pihost -F build/base.apk
(cd build && "$AAPT" add base.apk classes.dex)
# 4.5) 原生库（sherpa-onnx 语音识别）
if [ -d libs/aar-extract/jni/arm64-v8a ]; then
  mkdir -p build/lib/arm64-v8a
  cp libs/aar-extract/jni/arm64-v8a/libsherpa-onnx-jni.so build/lib/arm64-v8a/ 2>/dev/null || true
  cp libs/aar-extract/jni/arm64-v8a/libonnxruntime.so build/lib/arm64-v8a/ 2>/dev/null || true
  cp libs/aar-extract/jni/arm64-v8a/libsherpa-onnx-c-api.so build/lib/arm64-v8a/ 2>/dev/null || true
  (cd build && "$AAPT" add base.apk lib/arm64-v8a/libsherpa-onnx-jni.so lib/arm64-v8a/libonnxruntime.so lib/arm64-v8a/libsherpa-onnx-c-api.so >/dev/null)
fi

# 5) 签名（CI 从 secrets 解出；本机无则自动生成）
if [ ! -f pibridge.keystore ]; then
  keytool -genkeypair -keystore pibridge.keystore -alias pibridge -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass pibridge -keypass pibridge \
    -dname "CN=PiBridge" >/dev/null 2>&1
fi
"$APKSIGNER" sign --min-sdk-version 24 --ks pibridge.keystore \
  --ks-pass pass:pibridge --key-pass pass:pibridge \
  --out build/pibridge.apk build/base.apk

echo "✅ build/pibridge.apk $(stat -c%s build/pibridge.apk) bytes"
