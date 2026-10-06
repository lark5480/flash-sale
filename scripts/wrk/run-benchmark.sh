#!/bin/bash
# ============================================================
# 秒杀系统压测一键执行脚本
# ============================================================
#
# 前置条件：
#   1. 安装 wrk: https://github.com/wg/wrk
#   2. 确保秒杀系统已启动（Gateway 端口默认 8080）
#   3. 确保数据库中有测试数据（至少 1 个激活的秒杀活动）
#
# 用法：
#   chmod +x run-benchmark.sh
#   ./run-benchmark.sh [base_url] [flash_sale_id]
#
# 示例：
#   ./run-benchmark.sh http://localhost:8080 1
#
# ------------------------------------------------------------
# 2026-10-06 修正说明（三个真实缺陷，均有历史 results 与代码可佐证）：
#
#   缺陷 1：flash-sale-test.lua 把 scenario 硬编码为 "active_list"，而本脚本只替换 URL、
#           不替换该变量；又因 request() 用 wrk.format 显式传 path（覆盖 URL 的 path），
#           导致 A~E 五个"场景"实际全部请求 /api/flash-sale/active，只有并发数不同。
#           证据：results/20260730_165424/*.txt 五个文件的 done() 输出都写着「场景: active_list」。
#           修法：改为逐场景导出 SCENARIO 环境变量，由 Lua 读取。
#
#   缺陷 2：下单分支的路径 /api/flash-order/purchase 在控制器里从不存在
#           （真实接口为 POST /api/flash-sale/{flashSaleId}/purchase?captchaId=&captchaAnswer=，
#             且两个参数是 @RequestParam 而非请求体字段）。原脚本因缺陷 1 从未真正走到该分支，
#           所以一直没暴露；一旦走到即 100% 返回 500 "No static resource"。
#           修法：Lua 中改正路径与参数；本脚本把该场景改名为 D_purchase_reject 并注明
#           「wrk 只能测到验证码/限流拒绝路径的吞吐」。
#
#   缺陷 3：下单接口带 @RateLimit(permits=5, windowSeconds=5)，且每次请求需要一次性图形验证码，
#           压测工具无法逐请求求解。真实下单吞吐必须用顺序脚本验证，见 order-e2e.ps1。
# ============================================================

set -uo pipefail

# ==================== 配置 ====================

BASE_URL="${1:-http://localhost:8080}"
FLASH_SALE_ID="${2:-1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULT_DIR="${SCRIPT_DIR}/results/$(date +%Y%m%d_%H%M%S)"
WRK_THREADS=4

echo "=========================================="
echo "  秒杀系统压测"
echo "=========================================="
echo "  目标地址: ${BASE_URL}"
echo "  秒杀活动 ID: ${FLASH_SALE_ID}"
echo "  结果目录: ${RESULT_DIR}"
echo "=========================================="

mkdir -p "${RESULT_DIR}"
cd "${SCRIPT_DIR}" || exit 1

# ==================== 认证 ====================

echo ""
echo "  正在生成认证 Token..."

# 优先使用 Python 生成 JWT（绕过登录验证码），回退到 curl 登录
TOKEN=$(python3 -c "
import hmac, hashlib, base64, json, time

secret = 'flash-sale-secret-key-min-256-bits-long-for-hs256'

def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()

header = b64url(json.dumps({'alg': 'HS256', 'typ': 'JWT'}, separators=(',', ':')).encode())
now = int(time.time())
payload = b64url(json.dumps({
    'sub': '1',
    'role': 'ADMIN',
    'iat': now,
    'exp': now + 1800
}, separators=(',', ':')).encode())

signing_input = f'{header}.{payload}'.encode()
signature = b64url(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
print(f'{header}.{payload}.{signature}')
" 2>/dev/null || echo "")

# 如果 Python 不可用，回退到 curl 登录
if [ -z "${TOKEN}" ]; then
    echo "  Python 不可用，尝试 curl 登录..."
    TOKEN=$(curl -s -X POST "${BASE_URL}/admin/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"username":"admin","password":"admin123","captchaId":"bench","captchaCode":"0"}' \
        2>/dev/null | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4 || echo "")
fi

if [ -n "${TOKEN}" ]; then
    echo "  Token 获取成功: ${TOKEN:0:20}..."
    echo -n "${TOKEN}" > "${SCRIPT_DIR}/token.txt"
else
    echo "  警告: Token 获取失败，需要认证的接口压测将返回 401"
fi

# ==================== 工具函数 ====================

run_scenario() { # name threads connections duration scenario [note]
    local name="$1" threads="$2" connections="$3" duration="$4" scenario="$5" note="${6:-}"

    echo ""
    echo "------------------------------------------"
    echo "  场景: ${name}"
    echo "  线程: ${threads}, 并发: ${connections}, 时长: ${duration}"
    echo "  SCENARIO: ${scenario}"
    [ -n "${note}" ] && echo "  说明: ${note}"
    echo "------------------------------------------"

    SCENARIO="${scenario}" FLASH_SALE_ID="${FLASH_SALE_ID}" \
        wrk -t"${threads}" -c"${connections}" -d"${duration}" \
        -s "${SCRIPT_DIR}/flash-sale-test.lua" \
        "${BASE_URL}" \
        2>&1 | tee "${RESULT_DIR}/${name}.txt"

    echo "  结果已保存: ${RESULT_DIR}/${name}.txt"
}

# ==================== 压测场景 ====================

# 场景 A：基线测试（低并发，验证系统正常状态）
run_scenario "A_baseline" "${WRK_THREADS}" 10 30s active_list "低并发基线，预期零错误"

# 场景 B：活动列表热点（高并发读）
run_scenario "B_active_list" "${WRK_THREADS}" 100 30s active_list "GET /api/flash-sale/active"

# 场景 C：秒杀详情热点（单 key 高并发）
run_scenario "C_detail" "${WRK_THREADS}" 100 30s detail "GET /api/flash-sale/${FLASH_SALE_ID}"

# 场景 D：下单接口 —— 只能测"拒绝路径"
#   该接口带 @RateLimit(permits=5, windowSeconds=5)，且每次请求需要一次性图形验证码，
#   wrk 无法逐请求求解，因此这里测到的是验证码/限流拒绝路径的吞吐，不代表下单吞吐。
#   真实下单闭环请运行 order-e2e.ps1。
run_scenario "D_purchase_reject" "${WRK_THREADS}" 500 10s purchase \
    "⚠ 仅代表验证码/限流拒绝路径吞吐，不是下单吞吐；真实下单见 order-e2e.ps1"

# 场景 E：混合负载（70% 列表 / 20% 详情 / 10% 下单）
run_scenario "E_mixed" "${WRK_THREADS}" 100 30s mixed \
    "⚠ 含 10% 下单分支，该分支必然被拒，故必有一定比例非 2xx——不要当作成功率引用"

# ==================== 汇总 ====================

echo ""
echo "=========================================="
echo "  压测完成！"
echo "  结果保存在: ${RESULT_DIR}"
echo "=========================================="
echo ""
printf "%-22s %10s %10s %10s %10s %10s\n" "场景" "req/s" "P50(ms)" "P95(ms)" "P99(ms)" "非2xx"
echo "--------------------------------------------------------------------------------"
for f in "${RESULT_DIR}"/*.txt; do
    scenario=$(basename "$f" .txt)
    rps=$(grep -a "Requests/sec:" "$f" | tail -1 | awk '{print $2}')
    # wrk 自带 Thread Stats 段里的 Percentile 无明细，这里用 Lua done() 打印的延迟分布
    p50=$(grep -a "  P50" "$f" | tail -1 | awk '{print $3}')
    p95=$(grep -a "  P95" "$f" | tail -1 | awk '{print $3}')
    p99=$(grep -a "  P99  " "$f" | tail -1 | awk '{print $3}')
    non2xx=$(grep -a "Non-2xx or 3xx responses:" "$f" | tail -1 | awk '{print $5}')
    printf "%-22s %10s %10s %10s %10s %10s\n" \
        "${scenario}" "${rps:-N/A}" "${p50:-N/A}" "${p95:-N/A}" "${p99:-N/A}" "${non2xx:-0}"
done
echo "--------------------------------------------------------------------------------"
echo "提示：非2xx 占比高说明该场景的 req/s 不代表业务吞吐，引用前务必核对。"
echo "      下单链路的真实吞吐与闭环成功率请使用 order-e2e.ps1。"
echo "------------------------------------------"
