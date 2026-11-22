#!/bin/bash
# 重启本地开发实例（先按 PID 停掉 Maven 与已 fork 的应用 JVM）
set -e
for pid in $(/opt/jdk-25/bin/jps -l | grep -E 'FeeVerifyApplication|classworlds.launcher' | awk '{print $1}'); do
  kill "$pid" 2>/dev/null || true
done
sleep 3
cd /workspace/71
nohup /opt/maven/bin/mvn spring-boot:run > /tmp/app.log 2>&1 &
disown
for i in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/v3/api-docs 2>/dev/null || true)
  if [ "$code" = "200" ]; then echo "APP UP after ~$((i*2))s"; exit 0; fi
  sleep 2
done
echo "APP FAILED TO START"; tail -30 /tmp/app.log; exit 1
