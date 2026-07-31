-- flash-sale-auth.lua
-- wrk 登录准备脚本：获取 JWT Token
-- 用法：先通过 wrk 调用登录接口获取 token，保存为全局变量

-- 配置
local login_url = "/admin/auth/login"
local username = "admin"
local password = "admin123"

-- 全局变量存储 token（wrk 每个线程独立 Lua State，天然线程安全）
auth_token_value = nil

-- 请求函数
request = function()
    local body = '{"username":"' .. username .. '","password":"' .. password .. '","captchaId":"test","captchaCode":"0"}'
    wrk.method = "POST"
    wrk.headers["Content-Type"] = "application/json"
    wrk.body = body
    return wrk.format(nil, login_url, wrk.headers, body)
end

-- 响应处理：提取 token
response = function(status, headers, body)
    if status == 200 then
        -- 尝试从 JSON 响应中提取 accessToken
        local token = body:match('"accessToken":"([^"]+)"')
        if token then
            -- 保存到全局变量供其他脚本使用
            auth_token_value = token
            print("Login successful, token obtained: " .. string.sub(token, 1, 20) .. "...")
        else
            print("Login response: " .. body)
        end
    else
        print("Login failed with status: " .. status)
    end
end
