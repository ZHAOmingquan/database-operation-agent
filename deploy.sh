#!/usr/bin/env bash
# 一键部署：打包 → 拷贝到 deploy/ → 重启应用 → 健康检查。
# 应用始终从 deploy/ 下的副本运行：mvn package 重建 target jar 时不会破坏
# 运行中 JVM 的类加载（Spring Boot 可执行 jar 被原地替换会导致 NoClassDefFoundError / 504）。
set -euo pipefail
cd "$(dirname "$0")"

./mvnw package

mkdir -p deploy
cp target/database-operation-agent-1.0.0.jar deploy/database-operation-agent.jar

PID=$(ss -tlnp 2>/dev/null | grep ':18080' | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$PID" ]; then
  echo "stopping old instance (pid $PID)"
  kill "$PID"
  for i in $(seq 1 30); do
    ss -tlnp 2>/dev/null | grep -q ':18080' || break
    sleep 1
  done
fi

echo "starting new instance..."
nohup java -jar deploy/database-operation-agent.jar > nohup.out 2>&1 &

for i in $(seq 1 60); do
  sleep 2
  if curl -sf -o /dev/null "http://127.0.0.1:18080/api/dicts?type=model_provider" 2>/dev/null; then
    echo "deploy OK: app is healthy on :18080"
    exit 0
  fi
done
echo "app did not become healthy in time, check nohup.out" >&2
exit 1
