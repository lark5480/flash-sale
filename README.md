# Flash Sale - 秒杀系统

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
| CI | GitHub Actions（Maven 构建 + Artifact 上传） |

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
- 接口限流（@RateLimit 注解 + Redis ZSET 滑动窗口）—— **控制层限流**
  - 秒杀下单：5 次 / 5 秒
  - C 端登录：5 次 / 60 秒
  - 注册：3 次 / 60 秒
  - 管理端登录：3 次 / 60 秒
- 熔断降级（@SentinelResource + Sentinel Dashboard）—— **业务层流控/熔断**
  - 秒杀下单方法 `FlashOrderServiceImpl.purchase()` 标注 `@SentinelResource`
  - blockHandler 返回"系统繁忙，请稍后重试"，fallback 兜底业务异常
  - Sentinel Dashboard（:8718）动态推送流控/熔断/热点规则
- 验证码（算术题 + Redis 存储，一次性消费）
- JWT 密钥生产环境走环境变量 ${JWT_SECRET}
- 异常分类处理（业务异常吞没，系统异常 re-throw 触发 MQ 重试）

### 消息可靠性
- RocketMQ Broker `flushDiskType = SYNC_FLUSH`（同步刷盘，消息不丢）
- Consumer `maxReconsumeTimes = 3`（重试 3 次后进死信队列）
- 死信队列消费者 `FlashOrderDeadLetterConsumer` 记录重试耗尽消息，供人工补偿
- Broker 原生 Prometheus 指标导出（端口 5557）

### 可观测性
- **Actuator + Micrometer + Prometheus + Grafana** 全链路监控
  - `/actuator/prometheus` 暴露 JVM / HTTP / 自定义业务指标
  - 自定义业务指标：`flashsale.order.success` / `flashsale.order.fail` / `flashsale.order.duration`（含 SLO 分桶 50ms/100ms/500ms/1s/5s + 百分位直方图）
  - Prometheus（:9090）拉取指标，Grafana（:3000，admin/admin）可视化大盘
  - Dashboard 自动加载（Provisioning）：数据源 + 看板 JSON 版本控制，重启不丢失
  - Dashboard：JVM 堆内存 / GC / CPU / HTTP QPS & P99 / 下单成功失败 & QPS & 成功率
  - ⚠️ 关键配置：`management.metrics.distribution.percentiles-histogram.http.server.requests: true`（暴露 `_bucket` 指标，否则 P99 计算为 "No data"）
  - ⚠️ 后端跑在宿主机：`docker/prometheus/prometheus.yml` 的 targets 已默认指向 `host.docker.internal:8081` / `8082` / `8080`；若改为全容器部署（api/admin/gateway 也进 Compose），需把对应 target 改回容器名 `api:8081` / `admin:8082` / `gateway:8080`

### 自动化
- 秒杀活动状态自动流转（定时任务：待开始 -> 进行中 -> 已结束）
- 订单超时自动取消（15 分钟未支付，自动归还 DB + Redis 库存，递减用户购买计数）
- Token 刷新
- 启动时自动初始化默认管理员账号（admin / admin123）
- GitHub Actions CI：push 到 master/dev 自动构建，产物上传 Artifact

### 缓存策略
- **三级缓存**：L1 Caffeine（秒级 TTL）→ L2 Redis（分钟级 TTL）→ DB 兜底回源，活动列表同样走三级缓存
- **写操作同时失效两级缓存**，读请求逐级回源并回填
- **缓存三级防护**：穿透防护——空值标记（`@@NULL@@`）+ 短 TTL 兜底；雪崩防护——`randomTtl()` 基于 `ThreadLocalRandom` 叠加 ±300s 随机偏移；击穿防护——Caffeine `get(key, fn)` per-key 同步 + Redis `setIfAbsent`（SETNX）原子回填
- **Redis 缓存预热**：秒杀激活时自动写入库存 + 详情缓存
- **Redis SETNX 原子扣库存**：单次 RTT 完成限购检查 + 库存扣减

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

### 方式一：Docker Compose 一键部署（推荐）

```bash
# 1. 构建前端产物（Docker 后端镜像会自动编译）
cd flash-frontend && npm install && npm run build && cd ..
cd flash-admin-frontend && npm install && npm run build && cd ..

# 2. 一键启动（中间件 + 后端 + 前端）
docker compose up -d

# 3. 初始化数据库表
docker compose exec -T mysql mysql -uroot -proot123 flash_sale < sql/init.sql
```

> 前端产品构建一次即可，后续修改前端代码需要重新 `npm run build`。
> 若只需中间件（本地开发后端），运行：`docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker`
> 启动监控栈：`docker compose up -d prometheus grafana node-exporter sentinel-dashboard`

### 方式二：本地手动启动

#### 1. 初始化数据库

```bash
mysql -u root -p < sql/init.sql
```

#### 2. 启动中间件

确保 MySQL、Redis、Nacos、RocketMQ 均已启动。

#### 3. 启动后端服务

```bash
# 编译
mvn clean package -DskipTests

# 按顺序启动（网关最后）
java -jar flash-api/target/flash-api-1.0.0.jar
java -jar flash-admin/target/flash-admin-1.0.0.jar
java -jar flash-gateway/target/flash-gateway-1.0.0.jar
```

#### 4. 启动前端

```bash
# 用户端
cd flash-frontend && npm install && npm run dev

# 管理端
cd flash-admin-frontend && npm install && npm run dev
```

### 5. 访问

| 服务 | 地址 |
|------|------|
| 网关（统一入口） | http://localhost:8080 |
| 用户端前端 | http://localhost:5173 |
| 管理端前端 | http://localhost:5174 |
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
| GET | /api/flash-sale/active | 进行中的秒杀活动 | 是 |
| GET | /api/flash-sale/{id} | 秒杀活动详情 | 是 |
| POST | /api/flash-sale/{id}/purchase | 秒杀下单（需验证码） | 是 |
| GET | /api/order/status?messageKey= | 轮询订单状态 | 是 |
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
客户端轮询 /order/status 获取结果
```

## 文档

| 文档 | 说明 |
|------|------|
| [01-架构概览](docs/01-architecture.md) | 系统架构、模块职责、核心链路、数据设计、安全认证 |
| [02-部署指南](docs/02-deployment.md) | 中间件 Docker 配置、数据库初始化、服务启动、常见问题排查 |
| [03-开发指南](docs/03-development.md) | API 接口文档、包结构规范、枚举值、代码规范、前端开发 |
