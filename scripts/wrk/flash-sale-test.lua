-- flash-sale-test.lua
-- 秒杀系统核心压测脚本
--
-- 使用方式（2026-10-06 修正）：场景由环境变量 SCENARIO 决定，不再需要手工改脚本。
-- 注意：wrk 命令行上的 URL 仅用于确定 host，请求路径由本脚本按 SCENARIO 生成
-- （request() 里 wrk.format 传了显式 path，会覆盖 URL 的 path —— 这正是原版 5 个"场景"
--   实际全打同一个接口的原因）。
--
--   SCENARIO=active_list  wrk -t4 -c100 -d30s -s flash-sale-test.lua http://localhost:8080   # 活动列表热点
--   SCENARIO=detail       wrk -t4 -c100 -d30s -s flash-sale-test.lua http://localhost:8080   # 活动详情热点
--   SCENARIO=mixed        wrk -t4 -c100 -d30s -s flash-sale-test.lua http://localhost:8080   # 混合 70:20:10
--   SCENARIO=purchase     wrk -t4 -c500 -d10s -s flash-sale-test.lua http://localhost:8080   # 下单（wrk 只能测拒绝路径）
--
--   可选环境变量：FLASH_SALE_ID（默认 1）、TOKEN_FILE（默认 ./token.txt，需先在当前目录准备好 JWT）
--   推荐直接执行 run-benchmark.sh，一次跑完全部场景并输出汇总表。

-- ==================== 配置区 ====================

-- 目标服务器（可通过命令行参数覆盖）
local target_host = "http://localhost:8080"

-- 测试场景：active_list / detail / purchase / mixed
-- 修正（2026-10-06）：原版此处硬编码为 "active_list"，而 run-benchmark.sh 只替换 URL、
-- 不替换本变量；又因 request() 用 wrk.format(method, path, ...) 显式传 path（会覆盖 URL 的 path），
-- 导致 5 个"场景"实际全部请求 /api/flash-sale/active，只是并发数不同。
-- 证据：scripts/wrk/results/20260730_165424/*.txt 的 done() 输出五个文件都写着「场景: active_list」。
-- 现改为从环境变量读取，由 run-benchmark.sh 逐场景注入 SCENARIO。
local scenario = os.getenv("SCENARIO") or "active_list"

-- 秒杀活动 ID（用于 detail 和 purchase 场景）
local flash_sale_id = os.getenv("FLASH_SALE_ID") or "1"

-- token 文件路径（可由 TOKEN_FILE 覆盖，便于从其他工作目录调用）
local token_path = os.getenv("TOKEN_FILE") or "token.txt"

local valid_scenarios = { active_list = true, detail = true, purchase = true, mixed = true }
if not valid_scenarios[scenario] then
    print("[flash-sale-test] WARN: 未知 SCENARIO='" .. scenario .. "'，回退 active_list")
    scenario = "active_list"
end

-- 认证 Token：从 token.txt 读取（由 run-benchmark.sh 预先生成）
local auth_token = nil
local token_file = io.open(token_path, "r")
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
            -- 修正（2026-10-06）：真实接口是 POST /api/flash-sale/{flashSaleId}/purchase，
            -- 且 captchaId / captchaAnswer 是 @RequestParam（query 参数），不是请求体字段。
            path = "/api/flash-sale/" .. flash_sale_id .. "/purchase?captchaId=bench-test&captchaAnswer=0"
            method = "POST"
            body = nil
        end
    elseif scenario == "active_list" then
        path = "/api/flash-sale/active"
        method = "GET"
    elseif scenario == "detail" then
        path = "/api/flash-sale/" .. flash_sale_id
        method = "GET"
    elseif scenario == "purchase" then
        -- 修正（2026-10-06）：原路径 /api/flash-order/purchase 在控制器里从不存在（git log -S 可证），
        -- 一旦真正走到该分支即 100% 返回 {"code":500,"msg":"No static resource api/flash-order/purchase."}。
        -- 真实接口：POST /api/flash-sale/{flashSaleId}/purchase?captchaId=&captchaAnswer=
        -- 注意：该接口带 @RateLimit(permits=5, windowSeconds=5) 且每次请求需要一次性图形验证码，
        -- 故 wrk 在此只能测"验证码/限流拒绝路径"的吞吐；真实下单闭环请用 order-e2e 脚本。
        path = "/api/flash-sale/" .. flash_sale_id .. "/purchase?captchaId=bench-test&captchaAnswer=0"
        method = "POST"
        body = nil
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
-- 注意（2026-10-06）：wrk 的 done() 只被调用一次，而 response() 是逐线程 Lua State 各自执行的，
-- 因此在本脚本里做"全局状态码统计"并不可行（会静默打印空表）。
-- 错误数请直接读 wrk 自带的输出行：「Non-2xx or 3xx responses: N」，run-benchmark.sh 的汇总会提取它。
response = function(status, headers, body)
    counter = counter + 1
    if status >= 400 then
        -- 记录错误（仅打印前 5 个，避免刷屏掩盖 wrk 汇总）
        if counter <= 5 then
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
    print("  ⚠ 错误数请以上方 wrk 自带的「Non-2xx or 3xx responses」为准（本块不含状态码分布，原因见 response() 注释）")
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
