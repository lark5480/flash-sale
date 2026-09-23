# Flash Sale - 秒杀系统

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

基于 Spring Cloud 微服务架构的秒杀系统，支持高并发场景下的商品秒杀、库存扣减和订单管理。

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 3.2.0 + Spring Cloud 2023.0.0 |
| 注册/配置中心 | Nacos（仅服务发现，配置本地化管理） |
| 网关 | Spring Cloud Gateway |
| ORM | MyBatis-Plus 3.5.5 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 + Caffeine 3.1.8（L1）+ Redisson 3.24.3（分布式锁） |
| 消息队列 | RocketMQ 5.3.0 (server) + rocketmq-spring-boot-starter 2.3.0 (client) |
| 认证 | JWT (jjwt 0.12.3, 双 Token: accessToken + refreshToken) |
| 监控 | Spring Boot Actuator + Micrometer + Prometheus + Grafana |
| 熔断降级 | Sentinel 1.8.8（@SentinelResource 业务层限流/熔断） |
| 前端 | Vue 3 + Vite + Element Plus (管理端) |
| 部署 | Docker Compose（14 服务编排） |
| 测试 | JUnit 5 + AssertJ + Mockito；Testcontainers 起真实 Redis 验证库存 Lua（用例清单见[测试](docs/development/testing.md)） |
| CI | GitHub Actions（`mvn -B clean verify`：测试 + 构建 + Artifact 上传） |

## 项目结构

```
flash-sale
├── flash-common        # 公共模块：统一返回、异常处理、JWT工具、雪花ID、Long→String 序列化
├── flash-model         # 数据模型：实体、DTO、VO、枚举
├── flash-mapper        # 数据访问：MyBatis-Plus Mapper
├── flash-service       # 业务逻辑：Service 实现、三级缓存配置、MQ 生产者/消费者
├── flash-api           # 用户端 REST API（端口 8081，启用 MQ 消费者）
├── flash-admin         # 管理端 REST API（端口 8082，不含 MQ 消费者隔离）
├── flash-gateway       # Spring Cloud Gateway 网关（端口 8080）
├── flash-frontend      # 用户端前端（Vue 3）
├── flash-admin-frontend # 管理端前端（Vue 3 + Element Plus）
├── docker              # Docker 配置文件（RocketMQ broker 配置等）
├── docs                # 架构/部署/开发文档
└── sql                 # 数据库初始化脚本
```

## 核心功能

### 用户端
- 注册 / 登录（JWT 双 Token：accessToken + refreshToken）
- 验证码校验（算术题验证码，防撞库/防刷）
- 浏览商品列表、查看商品详情
- 查看进行中的秒杀活动（倒计时：≥ 1 天显示 `3天 02:15:09`，< 1 天显示 `02:15:09`，< 1 小时红色紧急态，< 5 分钟最后冲刺脉冲；未开始时显示「距开始」）
- 秒杀下单（验证码 + Redis Lua 原子扣库存 + RocketMQ 异步创建订单）
- 轮询订单处理状态
- 查看我的订单（关键词搜索 + 状态筛选 + 完整分页：首页/末页/跳页/每页条数）
- 支付订单 / 取消订单 / 退款 / 删除已取消订单

### 管理端
- 管理员登录（验证码校验）
- 数据控制台（今日订单/成交额 KPI、近 7 日订单趋势、订单状态分布、进行中秒杀与最近订单快捷看板）
- 商品 CRUD（上架/下架；上架商品需先下架后才能编辑/删除，被秒杀场次引用的商品不可删除）
- 秒杀活动 CRUD + 状态管理（激活时自动预热 Redis 缓存，更新时自动清除缓存）
- 订单列表查看 / 支付 / 退款 / 删除已取消订单
- 用户列表 + 启用/禁用

### 安全防护
- 鉴权与权限：网关与 flash-api 各持一份放行清单、必须两处同步，只改一处会造出裂口；对外行为口径——游客可浏览注册/登录/验证码、秒杀列表与 GET 详情，下单/订单类接口一律要求登录（**游客能看不能买**）；返回码：**未登录 401、权限不足 403**。具体路径清单与改动约束的**权威真源**见[安全与认证](docs/architecture/security.md)
- 接口限流（@RateLimit 注解 + Redis ZSET 滑动窗口）—— **控制层限流**：秒杀下单 5 次/5 秒，C 端登录 5 次/60 秒，注册与管理端登录 3 次/60 秒；逐接口权威口径见[安全与认证 §5 接口限流](docs/architecture/security.md#5-接口限流)
- 熔断降级（@SentinelResource + Sentinel Dashboard）—— **业务层流控/熔断**
  - 秒杀下单方法 `FlashOrderServiceImpl.purchase()` 标注 `@SentinelResource`
  - blockHandler 返回"系统繁忙，请稍后重试"，fallback 兜底业务异常
  - Sentinel Dashboard（:8718）动态推送流控/熔断/热点规则
- 验证码（算术题 + Redis 存储，一次性消费）
- 签名密钥分环境边界：dev 与 docker profile 各带一把**仅本地可用**的默认 key（克隆下来零配置就能跑，开源 demo 的可接受取舍）；prod profile 走 `${JWT_SECRET}` 无默认值、`JwtUtil` 启动即校验（缺失/空白/短于 32 字节拒绝启动）。换真密钥参考 `.env.example`，机制细节的**真源**见[安全与认证 §1](docs/architecture/security.md#1-jwt-双-token-机制)
- 密钥防泄露三道闸：本地 pre-commit 钩子（启用：`git config core.hooksPath scripts/git-hooks`）→ CI `secret-scan` job 扫提交历史（gitleaks，`--redact`）→ `.gitignore` 覆盖 `.env` 与压测产物。注意 gitleaks 抓不到配置里的低熵口令，"扫描通过"不等于"仓库里没有明文密钥"；启用方式与操作细节见 [CONTRIBUTING §密钥门禁三道闸](CONTRIBUTING.md#密钥门禁三道闸)
- 异常分类处理（业务异常吞没，系统异常 re-throw 触发 MQ 重试）

### 消息可靠性
- RocketMQ Broker `flushDiskType = SYNC_FLUSH`（同步刷盘，消息不丢）
- Consumer `maxReconsumeTimes = 3`（重试 3 次后进死信队列）
- 死信队列消费者 `FlashOrderDeadLetterConsumer` 记录重试耗尽消息供人工补偿，并补写 `FAILED:原因` 终态标记，避免客户端永远轮询到 PROCESSING
- 业务终态失败写 `FAILED:原因`（如「已达每人限购数量」），`/api/order/status` 解出 `status` + `failReason` 回传前端
- Broker 原生 Prometheus 指标导出（端口 5557）

### 可观测性
- **Actuator + Micrometer + Prometheus + Grafana** 全链路监控
  - `/actuator/prometheus` 暴露 JVM / HTTP / 自定义业务指标
  - 自定义业务指标：`flashsale.order.success` / `flashsale.order.fail` / `flashsale.order.duration`（含 SLO 分桶 50ms/100ms/500ms/1s/5s + 百分位直方图）
  - Prometheus（:9090）拉取指标，Grafana（:3000，admin/admin）可视化大盘
  - Dashboard 自动加载（Provisioning）：数据源 + 看板 JSON 版本控制，重启不丢失
  - Dashboard：JVM 堆内存 / GC / CPU / HTTP QPS & P99 / 下单成功失败 & QPS & 成功率
  - ⚠️ 两个部署关键坑（P99 依赖 `_bucket` 直方图开关、Prometheus 抓取地址随宿主机/全容器部署切换）详见[可观测性](docs/architecture/observability.md)与[全量容器化部署](docs/deployment/full-container.md)

### 自动化
- 秒杀活动状态自动流转（定时任务：待开始 -> 进行中 -> 已结束）
- 订单超时自动取消（15 分钟未支付，自动归还 DB + Redis 库存，递减用户购买计数）
- Token 刷新
- 启动时自动初始化默认管理员账号（admin / admin123）
- GitHub Actions CI：push 到 master/dev 自动跑 `mvn -B clean verify`（测试 + 构建），产物上传 Artifact

### 缓存策略
- **三级缓存**：L1 Caffeine（秒级 TTL）→ L2 Redis（分钟级 TTL）→ DB 兜底回源，活动列表同样走三级缓存
- **写操作同时失效两级缓存**，读请求逐级回源并回填
- **缓存三级防护**：穿透防护——空值标记（`@@NULL@@`）+ 短 TTL 兜底；雪崩防护——`randomTtl()` 基于 `ThreadLocalRandom` 叠加 ±300s 随机偏移；击穿防护——Caffeine `get(key, fn)` per-key 同步 + Redis `setIfAbsent`（SETNX）原子回填
- **Redis 缓存预热**：秒杀激活时自动写入库存 + 详情缓存
- **Redis Lua 原子扣库存**：单次 RTT 完成限购检查 + 库存扣减

## 环境依赖

| 组件 | 版本 | 默认端口 |
|------|------|----------|
| JDK | 21+ | - |
| MySQL | 8.0 | 3306 |
| Redis | 7 | 6379 |
| Nacos | 2.x | 8848 |
| RocketMQ | 5.3.0 | 9876 (NameServer) |
| Node.js | 18+ | - |

## 快速启动

### 方式一：Docker Compose 全量部署（本项目未采用，日常请用方式二）

compose 文件按「**只用 Docker 起中间件**」维护，后端/前端容器这一段保留着但**不保证可用**：全量启动当前已知有 5 处卡点（Compose bake 构建 panic、broker 注册地址、前端 nginx 未反代、Nacos 全新实例初始化、Rancher/WSL2 宿主端口转发失效），其中最后一道属虚拟化层网络、不在仓库能解决范围内。

> 逐项结论、绕法与命令顺序已归档到[全量容器化部署](docs/deployment/full-container.md)，此处不再展开。日常开发请用方式二。

### 方式二：本地手动启动（日常开发用这个）

中间件用 Docker，后端与前端在宿主机跑 —— 秒杀全链路（下单 → Redis 预扣 → MQ → 消费落库 → 取消归还 → 重试与死信）已在此形态下完整验证过。**权威完整步骤**（含中间件状态验证、全新 Nacos 卷的管理员初始化、监控栈启动）见[本地开发部署](docs/deployment/local.md)，最小可跑路径：

```bash
# 1. 中间件 + 数据库初始化（数据在 flash-mysql-data 卷里，建过一次即可，down -v 之后要重来）
docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker
docker compose exec -T mysql mysql -uroot -proot123 flash_sale < sql/init.sql

# 2. 编译（跑全部后端测试；无 Docker 时真实 Redis 的集成测试整类跳过）
mvn clean verify

# 3. 按顺序启动后端（网关最后：网关经 Nacos 发现服务，业务服务未注册时路由不通）
java -jar flash-api/target/flash-api-1.0.0.jar
java -jar flash-admin/target/flash-admin-1.0.0.jar
java -jar flash-gateway/target/flash-gateway-1.0.0.jar

# 4. 启动前端
cd flash-frontend && npm install && npm run dev          # 用户端
cd flash-admin-frontend && npm install && npm run dev    # 管理端
```

后端启动报 `NacosException: user not found!`（仅全新 Nacos 卷需要）时，先执行[本地开发部署](docs/deployment/local.md) §2.3 的一次性初始化命令；其他问题先查[常见问题排查](docs/deployment/troubleshooting.md)。

#### 访问

| 服务 | 地址 |
|------|------|
| 网关（统一入口） | http://localhost:8080 |
| 用户端前端 | http://localhost:5173 |
| 管理端前端 | http://localhost:5174 |
| Nacos 控制台 | http://localhost:8848/nacos（`nacos/nacos`，全新实例需先按 §2.3 初始化） |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000（admin/admin） |
| Sentinel Dashboard | http://localhost:8718 |

默认管理员账号：`admin` / `admin123`

## API 概览

### 用户端（/api）

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | /api/auth/register | 注册 | 否 |
| POST | /api/auth/login | 登录（需验证码） | 否 |
| POST | /api/auth/refresh | 刷新 Token | 否 |
| GET | /api/auth/captcha | 获取验证码 | 否 |
| GET | /api/item/list | 商品列表 | 是 |
| GET | /api/item/{id} | 商品详情 | 是 |
| GET | /api/flash-sale/active | 进行中的秒杀活动 | 否（游客可浏览） |
| GET | /api/flash-sale/{id} | 秒杀活动详情 | 否（仅 GET，游客可浏览） |
| POST | /api/flash-sale/{id}/purchase | 秒杀下单（需验证码） | 是 |
| GET | /api/order/status?messageKey= | 轮询订单状态（PROCESSING / DONE / FAILED + `failReason`） | 是 |
| GET | /api/order/list?page=1&size=10&status=&keyword= | 我的订单（分页 + 状态筛选 + 关键词搜索） | 是 |
| GET | /api/order/{id} | 订单详情 | 是 |
| POST | /api/order/{id}/pay | 支付订单 | 是 |
| POST | /api/order/{id}/cancel | 取消订单 | 是 |
| POST | /api/order/{id}/refund | 退款 | 是 |
| DELETE | /api/order/{id} | 删除已取消订单 | 是 |

### 管理端（/admin）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /admin/auth/login | 管理员登录（需验证码） |
| GET | /admin/auth/captcha | 获取验证码 |
| GET/POST/PUT/DELETE | /admin/item/** | 商品管理 |
| GET/POST/PUT/DELETE | /admin/flash-sale/** | 秒杀活动管理 |
| PUT | /admin/flash-sale/{id}/status | 变更活动状态 |
| GET | /admin/order/list | 订单列表 |
| GET | /admin/order/{id} | 订单详情 |
| POST | /admin/order/{id}/pay | 支付订单 |
| POST | /admin/order/{id}/refund | 退款 |
| DELETE | /admin/order/{id} | 删除已取消订单 |
| GET | /admin/user/list | 用户列表（分页） |
| PUT | /admin/user/{id}/status | 启用/禁用用户 |

## 秒杀下单流程

```
用户请求 → Gateway 鉴权 → API 校验活动状态
    ↓
Redis Lua 原子扣库存（RTT < 1ms）
    ↓
RocketMQ 异步发送下单消息
    ↓
立即返回 messageKey（客户端轮询）
    ↓
Consumer 消费：幂等校验 → 分布式锁 → DB 乐观锁扣库存 → 创建订单
    ↓
客户端轮询 /order/status 获取结果（失败时带 failReason）
```

## 文档

文档已拆为按主题的 wiki 结构（可用 VitePress 构建为带侧栏与全文搜索的文档站，见下方）：

| 分区 | 页面 |
|------|------|
| **架构** | [系统总览](docs/architecture/overview.md) · [秒杀下单核心链路](docs/architecture/flash-sale-flow.md) · [数据设计](docs/architecture/data-design.md) · [安全与认证](docs/architecture/security.md) · [可观测性](docs/architecture/observability.md) · [定时任务与消费者隔离](docs/architecture/scheduling-and-isolation.md) |
| **部署** | [本地开发部署](docs/deployment/local.md) · [常见问题排查](docs/deployment/troubleshooting.md) · [全量容器化部署](docs/deployment/full-container.md) |
| **开发** | [模块依赖与包结构](docs/development/structure.md) · [API 接口文档](docs/development/api-reference.md) · [统一返回与枚举](docs/development/response-and-enums.md) · [Redis Lua 脚本](docs/development/redis-lua.md) · [RocketMQ 消息机制](docs/development/rocketmq.md) · [前端开发](docs/development/frontend.md) · [代码规范](docs/development/code-standards.md) · [测试](docs/development/testing.md) |
| **知识笔记** | [知识笔记与面试 Q&A](docs/notes/interview-qa.md) |
| **贡献** | [CONTRIBUTING](CONTRIBUTING.md)：本地跑起、分支/提交策略、测试与密钥门禁、版本冻结约束 |

### 本地预览文档站（VitePress）

```bash
npm install          # 安装 vitepress（仅文档站需要）
npm run docs:dev     # 本地预览 http://localhost:5173
npm run docs:build   # 构建静态站点到 docs/.vitepress/dist
```

## 姊妹项目：跨服务数据一致性

作者的姊妹项目 [mall-consistency-lab](https://github.com/lark5480/mall-consistency-lab) 是一个以**跨服务数据一致性**为核心设计目标的 Spring Cloud 电商系统，解决的是与本项目互补的问题：下单链路拆在三个独立库上，经 Feign 编排「远程扣库存 → 本地落单 → 失败补偿 → 定时对账」，不引入 Seata/MQ，靠 DB 唯一键幂等、状态机 CAS 与定时对账兜底保证最终一致。

两个项目处在同一决策空间的两端：

- **常规交易链路**（mall-consistency-lab 的领域）：QPS 有限、写跨多个服务，同步扣减能实时确认库存；异步化反而制造「下单成功但库存未扣」的窗口，补偿 + 对账比引入 MQ 更简单可解释。
- **秒杀热路径**（本项目的核心）：洪峰必须挡在 DB 之前，Redis Lua 原子预扣 + RocketMQ 异步削峰是必要手段，用轮询 `messageKey` 消化异步窗口。

两者并非对立方案：本项目消费端落库同样依赖 `message_key` 唯一索引幂等与「`UPDATE ... WHERE stock > 0`」条件更新——洪峰被缓存与消息层挡掉之后，落库层仍是同一套「唯一键原子裁决」原则。

## 许可证

本项目采用 [MIT](LICENSE) 许可证，与姊妹项目 [mall-consistency-lab](https://github.com/lark5480/mall-consistency-lab) 保持一致。
