#!/bin/bash
# 一键构建 release 发行包：jre21 + 单体 jar + 启动脚本 → zip
# 用法：./build-release.sh [版本号]   （默认取 pom.xml 中的 version）
set -e

cd "$(dirname "$0")"
VERSION=${1:-$(awk '/<artifactId>database-operation-agent<\/artifactId>/{f=1} f&&/<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' pom.xml)}
JAR="target/database-operation-agent-${VERSION}.jar"
DIST="database-operation-agent-${VERSION}"
OUT="target/release"
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

[ -f "$JAR" ] || { echo "未发现 $JAR，先执行全量构建..."; ./mvnw clean package -DskipTests; }

echo "==> 用 jlink 生成精简 JRE 21（$JAVA_HOME）"
rm -rf "$OUT/$DIST"
mkdir -p "$OUT/$DIST"
"$JAVA_HOME/bin/jlink" \
  --add-modules java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.unsupported,jdk.zipfs,jdk.management,jdk.naming.dns,jdk.naming.rmi \
  --include-locales=zh,en \
  --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
  --output "$OUT/$DIST/jre"

echo "==> 组装发行目录 $OUT/$DIST"
cp "$JAR" "$OUT/$DIST/"

cat > "$OUT/$DIST/start.sh" <<'EOF'
#!/bin/bash
# 一键启动数据库操作分析智能体（默认端口 18080，可用 PORT 环境变量覆盖）
cd "$(dirname "$0")"
export LANG=${LANG:-zh_CN.UTF-8}
exec ./jre/bin/java ${JAVA_OPTS:--Xms256m -Xmx512m} -jar database-operation-agent-1.0.0.jar "$@"
EOF
chmod +x "$OUT/$DIST/start.sh"

cat > "$OUT/$DIST/start.bat" <<'EOF'
@echo off
rem 一键启动数据库操作分析智能体（Windows，默认端口 18080）
cd /d %~dp0
if "%PORT%"=="" set PORT=18080
start "" /b "%~dp0jre\bin\java.exe" -Xms256m -Xmx512m -jar "%~dp0database-operation-agent-1.0.0.jar" --server.port=%PORT%
echo 启动中，请稍候访问 http://localhost:%PORT%
EOF

cat > "$OUT/$DIST/README.txt" <<EOF
数据库操作分析智能体 $VERSION - Release 包
==========================================

目录内容：
  jre/                                内置 Java 21 运行环境（无需安装 JDK）
  database-operation-agent-$VERSION.jar   单体可执行 jar（前端+后端+依赖）
  start.sh / start.bat                一键启动脚本（Linux/macOS / Windows）

Linux / macOS：
  ./start.sh            # 前台运行；访问 http://localhost:18080

Windows：
  双击 start.bat        # 访问 http://localhost:18080

说明：
  - 首次启动自动初始化 SQLite 配置库（./data/agent.db）与种子数据
  - 端口可用环境变量 PORT 覆盖，如：PORT=8080 ./start.sh
  - JVM 参数可用环境变量 JAVA_OPTS 覆盖（Linux/macOS）
  - 日志输出到 ./logs/database-operation-agent.log
  - 仓库地址：https://gitee.com/mingzy/database-operation-agent
  - License: Apache-2.0
EOF

echo "==> 打包 zip"
cd "$OUT"
rm -f "$DIST.zip"
zip -qr "$DIST.zip" "$DIST"
echo "完成：$OUT/$DIST.zip"
unzip -l "$DIST.zip" | tail -3
