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
# ============================================================

set -e

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

# 创建结果目录（基于脚本目录）
mkdir -p "${RESULT_DIR}"

# 切换到脚本目录（确保 Lua 脚本能找到 token.txt）
cd "${SCRIPT_DIR}"

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
    # 写入 token.txt 供 Lua 脚本读取
    echo -n "${TOKEN}" > "${SCRIPT_DIR}/token.txt"
else
    echo "  警告: Token 获取失败，需要认证的接口压测将返回 401"
fi

# ==================== 工具函数 ====================

run_scenario() {
    local name="$1"
    local threads="$2"
    local connections="$3"
    local duration="$4"
    local url="$5"
    local need_auth="${6:-false}"

    echo ""
    echo "------------------------------------------"
    echo "  场景: ${name}"
    echo "  线程: ${threads}, 并发: ${connections}, 时长: ${duration}"
    echo "  URL: ${url}"
    if [ "${need_auth}" = "true" ]; then
        echo "  认证: 需要 Token"
    fi
    echo "------------------------------------------"

    wrk -t${threads} -c${connections} -d${duration} \
        -s "${SCRIPT_DIR}/flash-sale-test.lua" \
        "${url}" \
        2>&1 | tee "${RESULT_DIR}/${name}.txt"

    echo "  结果已保存: ${RESULT_DIR}/${name}.txt"
}

# ==================== 压测场景 ====================

# 场景 A：基线测试（低并发，验证系统正常状态）
run_scenario "A_baseline" ${WRK_THREADS} 10 30s \
    "${BASE_URL}/api/flash-sale/active"

# 场景 B：活动列表热点（高并发读）
run_scenario "B_active_list" ${WRK_THREADS} 100 30s \
    "${BASE_URL}/api/flash-sale/active"

# 场景 C：秒杀详情热点（单 key 高并发）
run_scenario "C_detail" ${WRK_THREADS} 100 30s \
    "${BASE_URL}/api/flash-sale/${FLASH_SALE_ID}"

# 场景 D：秒杀下单高并发（模拟活动开始瞬间）
run_scenario "D_purchase" ${WRK_THREADS} 500 10s \
    "${BASE_URL}/api/flash-order/purchase?flashSaleId=${FLASH_SALE_ID}" "true"

# 场景 E：混合负载
run_scenario "E_mixed" ${WRK_THREADS} 100 30s \
    "${BASE_URL}"

# ==================== 汇总 ====================

echo ""
echo "=========================================="
echo "  压测完成！"
echo "  结果保存在: ${RESULT_DIR}"
echo "=========================================="
echo ""
echo "各场景 TPS 汇总："
echo "------------------------------------------"
for f in "${RESULT_DIR}"/*.txt; do
    scenario=$(basename "$f" .txt)
    rps=$(grep "Requests/sec:" "$f" 2>/dev/null | awk '{print $2}' || echo "N/A")
    echo "  ${scenario}: ${rps} req/s"
done
echo "------------------------------------------"
