-- flash-sale-test.lua
-- 秒杀系统核心压测脚本
--
-- 使用方式：
--   场景 A（基线测试）:
--     wrk -t4 -c10 -d30s -s flash-sale-test.lua http://localhost:8080/api/flash-sale/active
--
--   场景 B（活动列表热点）:
--     wrk -t4 -c100 -d30s -s flash-sale-test.lua http://localhost:8080/api/flash-sale/active
--
--   场景 C（秒杀详情热点）:
--     wrk -t4 -c100 -d30s -s flash-sale-test.lua http://localhost:8080/api/flash-sale/1
--     （其中 1 为已存在的秒杀活动 ID）
--
--   场景 D（秒杀下单高并发）:
--     wrk -t4 -c500 -d10s -s flash-sale-test.lua -- "http://localhost:8080/api/flash-order/purchase?flashSaleId=1"
--
--   场景 E（混合负载，需要修改 setup 中的权重配置）:
--     wrk -t4 -c100 -d30s -s flash-sale-test.lua http://localhost:8080

-- ==================== 配置区 ====================

-- 目标服务器（可通过命令行参数覆盖）
local target_host = "http://localhost:8080"

-- 测试场景：active_list / detail / purchase / mixed
local scenario = "active_list"

-- 秒杀活动 ID（用于 detail 和 purchase 场景）
local flash_sale_id = "1"

-- 认证 Token：从 token.txt 读取（由 run-benchmark.sh 预先生成）
local auth_token = nil
local token_file = io.open("token.txt", "r")
if token_file then
    auth_token = token_file:read("*l")
    token_file:close()
    if auth_token and #auth_token > 0 then
        print("[flash-sale-test] Token loaded from token.txt: " .. string.sub(auth_token, 1, 20) .. "...")
    else
        auth_token = nil
    end
else
    print("[flash-sale-test] token.txt not found, requests without auth")
end

-- 混合负载权重（百分比）
local mixed_weights = {
    active_list = 70,
    detail = 20,
    purchase = 10
}

-- ==================== 内部逻辑 ====================

local counter = 0
local request_count = 0

-- 构建不同场景的请求
request = function()
    local path, method, body, headers

    if scenario == "mixed" then
        -- 混合负载：按权重随机选择
        local rand = math.random(100)
        if rand <= mixed_weights.active_list then
            path = "/api/flash-sale/active"
            method = "GET"
        elseif rand <= mixed_weights.active_list + mixed_weights.detail then
            path = "/api/flash-sale/" .. flash_sale_id
            method = "GET"
        else
            path = "/api/flash-order/purchase?flashSaleId=" .. flash_sale_id
            method = "POST"
            body = '{"captchaId":"bench-test","captchaCode":"0"}'
        end
    elseif scenario == "active_list" then
        path = "/api/flash-sale/active"
        method = "GET"
    elseif scenario == "detail" then
        path = "/api/flash-sale/" .. flash_sale_id
        method = "GET"
    elseif scenario == "purchase" then
        path = "/api/flash-order/purchase?flashSaleId=" .. flash_sale_id
        method = "POST"
        body = '{"captchaId":"bench-test","captchaCode":"0"}'
    end

    headers = {
        ["Content-Type"] = "application/json",
        ["Accept"] = "application/json"
    }

    if auth_token then
        headers["Authorization"] = "Bearer " .. auth_token
    end

    request_count = request_count + 1

    if method == "POST" and body then
        return wrk.format(method, path, headers, body)
    else
        return wrk.format(method, path, headers)
    end
end

-- 响应统计
response = function(status, headers, body)
    counter = counter + 1
    if status >= 400 then
        -- 记录错误（仅打印前 10 个）
        if counter <= 10 then
            print("Error " .. status .. ": " .. string.sub(body or "", 1, 100))
        end
    end
end

-- 压测结束汇总
done = function(summary, latency, requests)
    print("\n========================================")
    print("  压测结果汇总")
    print("========================================")
    print(string.format("  场景: %s", scenario))
    print(string.format("  总请求数: %d", summary.requests))
    print(string.format("  总耗时: %.2fs", summary.duration / 1000000))
    print(string.format("  总数据量: %.2f MB", summary.bytes / 1024 / 1024))
    print(string.format("  请求/秒: %.2f", summary.requests / (summary.duration / 1000000)))
    print(string.format("  数据传输/秒: %.2f MB/s", summary.bytes / (summary.duration / 1000000) / 1024 / 1024))
    print("")
    print("  延迟分布:")
    print(string.format("    平均: %.2f ms", latency.mean / 1000))
    print(string.format("    最大: %.2f ms", latency.max / 1000))
    for _, p in ipairs({50, 75, 90, 95, 99, 99.9}) do
        local n = latency:percentile(p / 100)
        print(string.format("    P%-5s: %.2f ms", p, n / 1000))
    end
    print("========================================\n")
end
