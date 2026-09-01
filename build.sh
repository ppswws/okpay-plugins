#!/usr/bin/env bash
# build.sh - 编译并安装 okpay 插件
# 用法: ./build.sh [all|插件名]   默认 all 编译全部插件
# 例:   ./build.sh all           编译全部插件
#       ./build.sh alipay        仅编译 alipay 插件
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

target="${1:-all}"

if [ "$target" = "all" ]; then
  echo "📦 正在编译全部插件 ..."
  mvn clean install -DskipTests
else
  if [ ! -f "plugins/$target/pom.xml" ]; then
    echo "❌ 插件不存在: $target"
    echo "可用插件: $(ls plugins/)"
    exit 1
  fi
  echo "📦 正在编译插件 $target ..."
  mvn clean -pl "plugins/$target" -am install -DskipTests
fi

echo ""
echo "✅ 插件编译完成"
echo ""
