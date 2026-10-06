# 压测与缓存度量记录（2026-10-06）

::: tip 这是什么
一份**带日期的实测快照**，不是规范。目的是让 [interview-qa](./interview-qa.md) §八 与 README 里引用的数字有出处，并记录当时踩到的坑。数字会随机器与代码变化，**引用前请按 §6 自行复跑**。
:::

> **环境**：Windows 11 / 8 逻辑核 · 32 GB / Docker 28.3.3（Rancher Desktop）/ wrk 4.1.0（WSL2 Ubuntu-24.04）。
> **纪律**：本文所有数字均为实测，未实测的一律列入 §5「未测项」，不写估计值。

## 1. 读路径吞吐

### 1.1 第一次实测（修正场景切换前，但两个场景结果可用）

| 场景 | 并发 | req/s | P50 | P95 | P99 | 非 2xx |
|---|---|---|---|---|---|---|
| A 基线（活动列表） | 10 | 664.15 | 2.69 ms | 2.87 ms | 2.87 ms | 0 |
| B 活动列表 | 100 | 1661.75 | 12.62 ms | 14.98 ms | 15.09 ms | 0 |
| C 详情 | 100 | 2017.96 | 9.69 ms | 11.63 ms | 11.73 ms | 0 |

### 1.2 第二次实测（修正版脚本，全 5 场景）

| 场景 | SCENARIO | req/s | P50 | P95 | P99 | 非 2xx |
|---|---|---|---|---|---|---|
| A_baseline | `active_list` | 764.97 | 2.58 ms | 2.72 ms | 2.73 ms | 0 |
| B_active_list | `active_list` | 1966.10 | 10.80 ms | 12.99 ms | 13.10 ms | 0 |
| C_detail | `detail` | 2129.94 | 7.86 ms | 9.56 ms | 9.65 ms | 0 |
| D_purchase_reject | `purchase` | 927.92 | 142.95 ms | 161.16 ms | 161.33 ms | 9362 / 9367（全部 429 限流） |
| E_mixed | `mixed` | 1935.07 | 10.96 ms | 12.97 ms | 13.09 ms | 5692（9.8 %） |

### 1.3 结论（**引用时用区间，不要用单点**）

- 活动列表 c=100：约 **1.7–2.0k req/s**，P99 **13–15 ms**，**零错误**
- 活动详情 c=100：约 **2.0–2.1k req/s**，P99 **10–12 ms**，**零错误**

> 两次运行 B 场景 1662 → 1966、C 场景 2018 → 2130，**同机重复跑约 18 % 波动**。单点值在面试里容易被"我复现不出来"问住，区间更稳。

**E 场景 9.8 % 非 2xx 不是系统故障**：该场景含 10 % 下单分支，而 wrk 无法逐请求求解一次性图形验证码，这部分必然被拒。不要把它当作"混合负载成功率"。

## 2. 写路径：真实下单闭环

| 度量 | 实测值 |
|---|---|
| 闭环成功率 | **12 / 12 = 100 %**（取验证码 → 解算术 → 下单 → 轮询 `messageKey` 至 `DONE`） |
| 取验证码延迟 | P50 10.8 ms · P95 144.5 ms |
| 下单接口延迟 | P50 38.4 ms · P95 83.5 ms |
| 端到端（到订单 `DONE`） | P50 171.0 ms · P95 342.4 ms |

> 这是**唯一能代表"秒杀下单"的测量**。wrk 打这个接口测不到业务吞吐：接口带 `@RateLimit(permits = 5, windowSeconds = 5)`，且每次请求需要一次性图形验证码（Redis 校验后即失效），压测工具无法逐请求求解。故下单链路必须用顺序脚本单独验证（`scripts/wrk/order-e2e.ps1`）。

## 3. 缓存命中率

### 3.1 前提

`CacheConfig` 的三个 Caffeine 缓存本就调了 `recordStats()`，但原先只统计、未注册给 Micrometer，Prometheus 里没有任何 `cache_*` 指标。已改为在三个 `@Bean` 内各加一行 `CaffeineCacheMetrics.monitor(...)`（`micrometer-core` 与 `caffeine` 均已在 classpath，无新依赖、无行为改变）。

### 3.2 实测（宿主机起 flash-api，wrk 30s / 50 并发，直连 8081 绕开网关）

| 缓存 | 命中 | 未命中 | 命中率 | puts |
|---|---|---|---|---|
| `activeFlashSaleCache`（活动列表） | 37,622 | 1 | **99.997 %** | 1 |
| `flashSaleDetailCache`（活动详情） | 84,418 | 1 | **99.999 %** | 1 |
| `itemCache`（商品详情） | 0 | 0 | 未触及（本次未打商品接口） | 0 |

同期吞吐：活动列表 1248.44 req/s（P99 14.7 ms）；活动详情 2803.56 req/s（P99 4.8 ms）。

### 3.3 每请求仍有 1 次 Redis 读，这是设计而非缺陷

增量实验（200 次请求打 `/api/flash-sale/{id}`，前后对比 Redis `keyspace_hits`）：**增量恰好 200 → 每请求 1 次 Redis 读**。

原因：`FlashSaleServiceImpl.getDetailWithItem()` 调 `applyRealtimeStock(vo)`，**每次都从 Redis 读实时库存**。代码注释已写明：

> 故意不删 `flash:stock:{id}`：它是库存状态而非缓存，删掉等于把权威计数交给滞后的 DB 重建。

准确表述是：**静态 VO 走 L1（命中率 99.99 %），实时库存走 Redis 且有意不缓存**——命中率与数据新鲜度分离。L2 读只在 L1 未命中时触发，本次两个缓存各只 miss 1 次，基本未被触发。

::: warning 别把 Redis 全库命中率当成 L2 命中率
本次 Redis `keyspace_hits` 约 19.7 万，**主要来自 `applyRealtimeStock` 的库存读**，不是 L2 缓存命中。引用前先分清这两条路径。
:::

## 4. 当时发现的压测脚本缺陷（均已修复）

留在这里是因为它们解释了「为什么 2026-07-30 那批 results 的数值不能按场景名理解」。

| # | 缺陷 | 证据 | 修复提交 |
|---|---|---|---|
| 1 | `flash-sale-test.lua` 的 `scenario` 硬编码为 `active_list`，而 `run-benchmark.sh` 只替换 URL、不替换它；又因 `request()` 用 `wrk.format` 显式传 path（覆盖 URL 的 path），**五个"场景"实际全打同一个接口** | `scripts/wrk/results/20260730_165424/` 下五个文件的 `done()` 输出都写着「场景: active_list」；其中 `E_mixed` 的 **416.47 req/s** 即曾被引用的"混合场景 QPS 416+" | `abe9bb3` |
| 2 | 下单分支路径写成 `POST /api/flash-order/purchase?flashSaleId=`，真实接口是 `POST /api/flash-sale/{flashSaleId}/purchase?captchaId=&captchaAnswer=`（两个参数是 `@RequestParam`）。因缺陷 1 长期未被走到，一旦走到即 100 % 返回 `No static resource` | `git log -S"flash-order/purchase"` 显示该字符串只出现在新增压测脚本那次提交里，控制器中从未存在此路径 | `abe9bb3` |
| 3 | `docker/rocketmq/conf/broker.conf` 的 `brokerIP1 = 127.0.0.1` 是给宿主机应用用的；**容器内**的 api/admin 会把 `127.0.0.1` 解析成自己 → `RemotingConnectException: connect to 127.0.0.1:10911 failed` | 全量容器化下单返回 `{"code":500,"msg":"系统繁忙，请稍后重试"}` | `db95dba`（全栈模式改用 `broker-docker.conf`） |

另有两条写压测脚本时容易踩的坑：

- **`done()` 只被调用一次，而 `response()` 是逐线程 Lua State 各自执行** → 脚本内做"全局状态码统计"会静默打印空表。错误数请读 wrk 自带的 `Non-2xx or 3xx responses` 行。
- **成功码是 `ResultCode.SUCCESS(200)`，不是 0**；按「`code == 0` 判成功」写的客户端会把成功判成失败（第一版 `order-e2e.ps1` 即如此）。

## 5. 未测项（不要写进简历）

| 项 | 原因 |
|---|---|
| L2（Redis）缓存命中率 | 缺按 key 前缀区分的命中/未命中指标；`keyspace_hits` 混入了库存读，无法直接拆分 |
| 内存 / CPU / GC 水位 | 未采集（压测时未同步采样 `docker stats`） |
| 限流误伤率 | 未做（需统计 429 占比与业务请求占比） |
| 多实例（>1 个 api 副本）下的缓存行为 | 未做。L1 跨节点失效靠 Redis Pub/Sub 广播，但**未在多副本下实测** |

## 6. 复现命令

```bash
# 1) 起中间件（方式二：应用跑宿主机，broker 用 brokerIP1=127.0.0.1 正好适配）
docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker
docker compose exec -T mysql mysql -uroot -proot123 flash_sale < sql/init.sql   # 数据卷已初始化过可跳过

# 2) 读路径 + 混合（修正版脚本，场景由 SCENARIO 环境变量切换）
wsl -e bash -c "cd /mnt/f/.../scripts/wrk && ./run-benchmark.sh http://localhost:8080 1"

# 3) 真实下单闭环：先准备一批全新用户（同一用户受每人限购约束，会一直拿到 50002）
pwsh -File scripts/wrk/order-e2e.ps1 -Iterations 12 -MinIntervalMs 1300 -UserIdCsv "5,6,7,8"

# 4) 缓存命中率：宿主机构建并启动，然后用 cache-bench.sh 一条命令出结果
mvn -pl flash-api -am package -DskipTests && java -jar flash-api/target/flash-api-1.0.0.jar
bash scripts/wrk/cache-bench.sh http://<宿主IP>:8081 30s 50   # 直连 api，不是网关；宿主 IP 见下方注意
```

::: warning WSL 访问宿主机 Java 进程的坑
Tomcat 绑的是 IPv6 `::`，WSL 走 `localhost` / `127.0.0.1:8081` 会被拒（`Connection refused`）。必须用 `grep nameserver /etc/resolv.conf` 得到的宿主 IP（本次为 `172.30.16.1`）——这正是[本地开发部署](../deployment/local.md)里提过的跨环境访问办法。
:::

> **另**：`mall-consistency-lab` 的混沌测试度量（3 轮 `docker kill`、对账差异率 0 %、补偿成功率 100 %、恢复平均 24.6 s）记录在该仓库内，不在本页。
