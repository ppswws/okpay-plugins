#!/usr/bin/env bash
# build.sh - 编译并安装 okpay 插件
# 用法: ./build.sh [all|插件名]   默认 all 编译全部插件
# 例:   ./build.sh all           编译全部插件
#       ./build.sh alipay        仅编译 alipay 插件
# 编译产物统一收集到 build/（各插件主 jar，排除 sources/javadoc），避免逐个从 target 取。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

BUILD_DIR="$(pwd)/build"

# 收集插件主 jar 到 build/（排除 sources/javadoc）
collect_plugin_jars() {
  local src="$1"
  for jar in "$src"/target/*.jar; do
    [ -e "$jar" ] || continue
    case "$jar" in *-sources.jar|*-javadoc.jar) continue ;; esac
    mv -f "$jar" "$BUILD_DIR/"
  done
}

target="${1:-all}"

if [ "$target" = "all" ]; then
  echo "📦 正在编译全部插件 ..."
  mvn clean install -DskipTests
  rm -rf "$BUILD_DIR"
  mkdir -p "$BUILD_DIR"
  for plugin_dir in plugins/*/; do
    collect_plugin_jars "${plugin_dir%/}"
  done
else
  if [ ! -f "plugins/$target/pom.xml" ]; then
    echo "❌ 插件不存在: $target"
    echo "可用插件: $(ls plugins/)"
    exit 1
  fi
  echo "📦 正在编译插件 $target ..."
  mvn clean -pl "plugins/$target" -am install -DskipTests
  mkdir -p "$BUILD_DIR"
  collect_plugin_jars "plugins/$target"
fi

echo ""
echo "✅ 插件编译完成，产物已收集到 build/:"
ls -lh "$BUILD_DIR" | tail -n +2
echo ""
