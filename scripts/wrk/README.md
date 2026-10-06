# wrk 压测脚本

## 文件

| 文件 | 作用 |
|---|---|
| `run-benchmark.sh` | 一键跑完全部场景并输出汇总表（JWT 自动生成、结果落 `results/<时间戳>/`） |
| `flash-sale-test.lua` | wrk 请求编排脚本；场景由 **`SCENARIO` 环境变量**决定 |
| `order-e2e.ps1` | **真实下单闭环**顺序测试（取图形验证码 → 解算术 → 下单 → 轮询 `messageKey` 至 `DONE`） |
| `cache-bench.sh` | **缓存命中率度量**：造流量 + 读 `actuator/prometheus` 的 `cache_gets_total` + 算命中率，一条命令出结果（口径见 [docs/notes/benchmark-2026-10-06.md §3](../../docs/notes/benchmark-2026-10-06.md)） |
| `flash-sale-auth.lua` | 可选的"登录换 token"辅助脚本。`run-benchmark.sh` **不使用**它（改用 Python 直接签 JWT 以绕过验证码），保留作参考 |

## 快速开始

```bash
# 前提：秒杀系统已启动，且至少有 1 个进行中的秒杀活动
cd scripts/wrk
./run-benchmark.sh http://localhost:8080 1
```

```powershell
# 真实下单闭环（Windows 原生；需要一批全新用户，见下方「限购」说明）
pwsh -File scripts/wrk/order-e2e.ps1 -Iterations 12 -UserIdCsv "5,6,7,8,9,10,11,12"
```

```bash
# 缓存命中率（需先起中间件并让 flash-api 跑在宿主机；base_url 是直连 api，不是网关）
bash scripts/wrk/cache-bench.sh http://172.30.16.1:8081 30s 50
```

## 各场景真正测的是什么

| 场景 | SCENARIO | 接口 | 能否代表业务吞吐 |
|---|---|---|---|
| A_baseline | `active_list` | `GET /api/flash-sale/active` | ✅ 低并发基线 |
| B_active_list | `active_list` | `GET /api/flash-sale/active` | ✅ 读路径 |
| C_detail | `detail` | `GET /api/flash-sale/{id}` | ✅ 读路径 |
| **D_purchase_reject** | `purchase` | `POST /api/flash-sale/{id}/purchase` | ❌ **只能代表验证码/限流拒绝路径** |
| E_mixed | `mixed` | 70% 列表 / 20% 详情 / 10% 下单 | ⚠️ 含必然被拒的下单分支，**不可当成功率引用** |

### 为什么下单不能用 wrk 压

`FlashOrderController.purchase` 上有 `@RateLimit(permits = 5, windowSeconds = 5)`，且每次请求都要求一个**一次性图形验证码**（Redis 校验后即失效）。wrk 无法逐请求求解验证码，所以它打这个接口时，绝大多数请求在"验证码校验"这一步就被拒了——测到的是拒绝路径的吞吐。**真实下单吞吐与闭环成功率必须用 `order-e2e.ps1`。**

另一个容易踩的坑：该接口在验证码不通过时返回的是 **HTTP 200 + body `{"code":50006,...}`**，wrk 的 `Non-2xx or 3xx responses` **统计不到这类业务级失败**。所以看 D 场景时，`Non-2xx = 0` 并不代表请求都成功了。

## 2026-10-06 修正记录

原脚本有两个导致"压测结果名不副实"的缺陷，均有历史产物可佐证：

**缺陷 1 — 场景变量硬编码，五个场景实际打同一个接口**

`flash-sale-test.lua` 里 `local scenario = "active_list"` 是硬编码的，而 `run-benchmark.sh` 只替换 URL、不替换它；又因为 `request()` 用 `wrk.format(method, path, headers)` **显式传了 path**（会覆盖 URL 的 path），所以 A~E 五个"场景"全部请求 `/api/flash-sale/active`，只有并发数不同。

> 证据：`results/20260730_165424/` 下五个文件的 `done()` 输出**都写着「场景: active_list」**；其中 `E_mixed` 的 416.47 req/s 曾被引用为"混合场景 QPS 416+"。

修法：`scenario` / `flash_sale_id` / `token_file` 改为从环境变量读取，由 `run-benchmark.sh` 逐场景注入。

**缺陷 2 — 下单路径写错（因缺陷 1 而长期潜伏）**

| 项 | 原脚本 | 真实接口 |
|---|---|---|
| 路径 | `POST /api/flash-order/purchase?flashSaleId=1` | `POST /api/flash-sale/{flashSaleId}/purchase` |
| 参数 | body `{"captchaId":"...","captchaCode":"..."}` | **query** `?captchaId=&captchaAnswer=`（`@RequestParam`） |

`git log -S"flash-order/purchase"` 显示该字符串**只在新增压测脚本那次提交里出现过**，控制器中从未存在此路径。因缺陷 1，原脚本从未真正走到该分支，所以一直没暴露；一旦走到即 100% 返回 `{"code":500,"msg":"No static resource api/flash-order/purchase."}`。

**踩坑记录 — 别在 `done()` 里做全局状态码统计**

wrk 的 `response()` 是**逐线程 Lua State** 各自执行的，而 `done()` 只被调用一次，因此脚本内做"全局状态码统计"会静默打印空表。错误数请直接读 wrk 自带的 `Non-2xx or 3xx responses` 行（`run-benchmark.sh` 的汇总会提取它）。

## 限购与数据准备

同一用户在同一个活动上有购买上限（`flash_sale.limit_per_user`），所以：

- **用 wrk 压 `purchase`**：不受影响（请求都被验证码拦下了）；
- **用 `order-e2e.ps1` 验证闭环**：必须每轮换一个**全新用户**，否则会稳定拿到 `50002 已达到限购数量`。可直插一批用户绕开注册接口的限流（`@RateLimit(permits = 3, windowSeconds = 60)`）：

```sql
INSERT INTO flash_sale.user (username, password, role, status) VALUES
('bench_u1','x','USER',1), ('bench_u2','x','USER',1);
```

## 参考实测结果

见 `docs/notes/interview-qa.md` §八。要点（本机 8C32G、容器化部署、wrk 4 线程）：

- 读路径（本机 8C32G，容器化，4 线程）：活动列表 c=100 → **1662 req/s / P99 15.1 ms**；活动详情 c=100 → **2018 req/s / P99 11.7 ms**，均零错误；
- 下单闭环：**12/12 = 100 %**，下单接口 P95 **83.5 ms**，端到端到订单 `DONE` P95 **342 ms**。
