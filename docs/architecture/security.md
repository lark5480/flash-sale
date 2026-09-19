# 安全与认证

## 1. JWT 双 Token 机制

- **accessToken**：有效期 30 分钟，每次请求携带，用于身份校验
- **refreshToken**：有效期 7 天，accessToken 过期后用 refreshToken 换取新的 accessToken

| 参数 | 值 | 说明 |
|------|-----|------|
| jwt.secret | 256 位密钥 | HS256 签名密钥 |
| jwt.expiration | 1800000 ms | accessToken 有效期 30 分钟 |
| jwt.refresh-expiration | 604800000 ms | refreshToken 有效期 7 天 |

::: tip 密钥分环境边界
dev 与 docker profile 各带一把**仅本地可用**的默认 key（克隆下来零配置就能跑，开源 demo 的可接受取舍）；prod profile 走 `${JWT_SECRET}` 无默认值，且 `JwtUtil` 里不留任何代码兜底、启动即校验（缺失/空白/短于 32 字节直接拒绝启动）。真实密钥放 `.env`（已忽略）或平台密钥服务，参考 `.env.example`。
:::

## 2. 两道鉴权清单（必须同步）

::: danger 两处清单，只改一处会造出裂口
鉴权清单有**两处**：网关 `AuthGlobalFilter` 与 flash-api 的 `ApiSecurityConfig`。网关放行只代表请求能到下游，api 仍会二次判定。只改一处会造出「网关说公开、api 拒 403」的裂口（游客打不开 C 端首页与详情即此因，2026-09-18 端到端实跑查出，非代码审查可见）。

**返回码口径：无有效凭据 401、权限不足 403。**
:::

### 2.1 网关层放行清单（`AuthGlobalFilter`）

**白名单路径（无需 Token）：**

```
/api/auth/register        — 用户注册
/api/auth/login           — 用户登录
/api/auth/refresh         — 刷新 Token
/api/auth/captcha         — 验证码
/admin/auth/login         — 管理员登录
/admin/auth/captcha       — 管理员验证码
/api/flash-sale/active    — 进行中的秒杀列表（游客可浏览）
/api/flash-sale/{id}      — 秒杀详情（仅 GET 放行）
/images/**                — 商品图片（前缀放行）
```

**其他所有路径**均需在请求头中携带 `Authorization: Bearer <accessToken>`，缺失或无效返回 **401**。

### 2.2 api 层二次判定（`ApiSecurityConfig`）

flash-api 自己还有一道 `permitAll` 清单，**与网关白名单必须同步维护**。当前 api 侧放行 `/api/auth/**`、GET `/api/flash-sale/active` 与 GET `/api/flash-sale/{id:\d+}`（id 限定纯数字，避免游客借任意子路径打到 `/{id}` 处理器），其余一律要求认证。

返回码在 `ApiSecurityConfig.applyErrorResponses` 显式配置：无有效凭据 **401**、凭据有效但权限不足 **403**（Spring Security 默认的 `Http403ForbiddenEntryPoint` 会把两者一律变成裸 403，前端就分不清「该跳登录」还是「没权限」）。响应体统一为 `ResultVO`。

## 3. 身份信息传递

Gateway 校验 Token 通过后，从 JWT payload 中提取 `userId` 和 `role`，重写为 HTTP 请求头（`X-User-Id` / `X-User-Role`）转发给下游。

**⚠️ 该头不是信任边界**：客户端可以自带同名的 `X-User-Id` 请求头，但 `AuthGlobalFilter` 会对**所有**进入网关的请求先统一剥离再重写（放行的公开路径干脆不携带），因此下游看到的这两个头一定由网关写入。即便如此，下游仍不读取该头——每个服务的 `JwtAuthenticationFilter` 都会重新解析 `Authorization` 头中的 Token，以 JWT 中的 `userId` 作为 `Authentication` 的 principal；Controller 通过方法参数 `Authentication`（`auth.getPrincipal()`）获取当前用户。任何涉及归属/越权的判断都必须基于 Token 解析结果，禁止用 `X-User-Id` 请求头取值。

## 4. 验证码机制

登录和秒杀下单需验证码校验（`CaptchaService`）：

- **生成**：随机算术题（a + b / a - b / a × b），答案存入 Redis `captcha:{uuid}`，TTL 300s（`RedisConstants.CAPTCHA_KEY` / `CAPTCHA_TTL`）
- **校验**：比对用户输入与 Redis 中的答案，**无论对错都删除 key**（一次性消费）
- **端点**：`GET /api/auth/captcha`、`GET /admin/auth/captcha`

## 5. 接口限流

基于 `@RateLimit` 注解 + `RateLimitInterceptor` + Redis ZSET 滑动窗口：

| 接口 | 限制 |
|------|------|
| 秒杀下单 | 5 次 / 5 秒 |
| C 端登录 | 5 次 / 60 秒 |
| 注册 | 3 次 / 60 秒 |
| 管理端登录 | 3 次 / 60 秒 |

超限返回 HTTP 429 + `ResultCode.RATE_LIMITED(50007)`。未认证接口用客户端 IP 限流，已认证接口用 userId 限流。

::: tip 与 Sentinel 的分工
`@RateLimit` 是**控制层**限流（防刷/防撞库）；`@SentinelResource` 是**业务层**流控/熔断。区别见[可观测性](./observability.md#sentinel-熔断降级)。
:::

## 6. 密钥防泄露三道闸

1. 本地 pre-commit 钩子（启用：`git config core.hooksPath scripts/git-hooks`）
2. CI `secret-scan` job 扫提交历史（gitleaks，`--redact`）
3. `.gitignore` 覆盖 `.env` 与压测产物

注意 gitleaks 抓不到配置里的低熵口令，"扫描通过"不等于"仓库里没有明文密钥"。详见 [CONTRIBUTING](https://github.com/lark5480/flash-sale/blob/master/CONTRIBUTING.md#密钥门禁三道闸)。
