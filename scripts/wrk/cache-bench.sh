#!/usr/bin/env bash
# ============================================================
# 缓存命中率度量（L1 Caffeine）
# ============================================================
#
# 为什么单独一个脚本：wrk 打读接口只能给吞吐与延迟，**命中率读不出来**——必须读
# Micrometer 暴露的 cache_gets_total{cache=...,result="hit|miss"}。本脚本把三步合成一条命令：
#   造流量（wrk）→ 读指标（actuator/prometheus）→ 算命中率并打印。
# 配套说明见 docs/notes/benchmark-2026-10-06.md §3。
#
# 前置：
#   1) 中间件已起：docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker
#   2) flash-api 已启动（宿主机跑，见 README 方式二）且 CacheConfig 已注册 CaffeineCacheMetrics
#   3) 本机有 wrk
#
# 用法：bash scripts/wrk/cache-bench.sh [base_url] [duration] [connections]
#
#   base_url 是**直连 flash-api** 的地址，不是网关（网关没有 actuator）。
#   默认 http://localhost:8081
#
# ⚠ WSL 访问宿主机 Java 进程的坑：
#   Tomcat 绑的是 IPv6 ::，WSL 里走 localhost / 127.0.0.1:8081 会 Connection refused。
#   必须用 `grep nameserver /etc/resolv.conf` 得到的宿主 IP，例如：
#       bash scripts/wrk/cache-bench.sh http://172.30.16.1:8081 30s 50
# ============================================================

set -uo pipefail

HOST="${1:-http://localhost:8081}"
DUR="${2:-30s}"
CONN="${3:-50}"
FLASH_SALE_ID="${FLASH_SALE_ID:-1}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}" || exit 1

command -v wrk >/dev/null || { echo "缺少 wrk，无法压测"; exit 1; }
command -v curl >/dev/null || { echo "缺少 curl，无法读指标"; exit 1; }

# 该脚本自身不访问需要认证的接口；写个占位 token.txt 让 Lua 不至于警告
[ -s token.txt ] || echo -n "cache-bench" > token.txt

echo "=========================================="
echo "  缓存命中率度量"
echo "  目标（直连 api）: ${HOST}"
echo "  并发: ${CONN}   时长: ${DUR}   活动: ${FLASH_SALE_ID}"
echo "=========================================="

curl -s --max-time 8 "${HOST}/actuator/prometheus" >/dev/null 2>&1 \
  || { echo "无法访问 ${HOST}/actuator/prometheus —— 确认 flash-api 已启动且端口正确（不是网关端口）"; exit 1; }

for sc in active_list detail; do
  echo ""
  echo "------------------------------------------"
  echo "  场景 ${sc}（预热 L1 → 制造命中）"
  echo "------------------------------------------"
  SCENARIO="${sc}" FLASH_SALE_ID="${FLASH_SALE_ID}" TARGET_HOST="${HOST}" \
    wrk -t4 -c"${CONN}" -d"${DUR}" -s flash-sale-test.lua "${HOST}" 2>&1 \
    | grep -aE "Requests/sec|Non-2xx|  P50|  P95|  P99  " || true
done

echo ""
echo "=========================================="
echo "  缓存命中率（读 ${HOST}/actuator/prometheus）"
echo "=========================================="
METRICS="$(curl -s --max-time 10 "${HOST}/actuator/prometheus" | grep '^cache_gets_total' || true)"
if [ -z "${METRICS}" ]; then
  echo "未找到 cache_gets_total —— 检查 CacheConfig 是否已注册 CaffeineCacheMetrics"
  exit 1
fi

printf "%-26s %10s %10s %12s\n" "缓存" "命中" "未命中" "命中率"
echo "------------------------------------------------------------------"
for c in $(printf '%s\n' "${METRICS}" | sed -n 's/.*cache="\([^"]*\)".*/\1/p' | sort -u); do
  hit=$(printf '%s\n' "${METRICS}"  | grep "cache=\"${c}\".*result=\"hit\""  | awk '{s+=$NF} END{printf "%d", s+0}')
  miss=$(printf '%s\n' "${METRICS}" | grep "cache=\"${c}\".*result=\"miss\"" | awk '{s+=$NF} END{printf "%d", s+0}')
  tot=$(( hit + miss ))
  rate=$(awk -v h="${hit}" -v t="${tot}" 'BEGIN{ if (t>0) printf "%.3f %%", 100*h/t; else printf "n/a" }')
  printf "%-26s %10s %10s %12s\n" "${c}" "${hit}" "${miss}" "${rate}"
done
echo "------------------------------------------------------------------"
echo "注意：itemCache 只在打商品接口时才会被触及；本脚本只打活动列表与详情。"
