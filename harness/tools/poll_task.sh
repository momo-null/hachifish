#!/bin/bash
# M3' 真实 LLM 任务轮询
for i in $(seq 1 24); do
  sleep 10
  S=$(curl -s --connect-timeout 5 http://127.0.0.1:18071/status)
  PH=$(echo "$S" | grep -oE "'phase': '[a-z]+'" | head -1)
  echo "poll$i $PH"
  if echo "$PH" | grep -qE "done|error"; then
    echo "== metrics =="
    curl -s http://127.0.0.1:18071/metrics
    echo
    break
  fi
done
