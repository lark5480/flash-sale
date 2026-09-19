# 本地开发部署

本文档面向新加入的开发者，帮助你在本地快速搭建完整的开发环境。推荐形态是「**中间件容器化 + 后端/前端在宿主机跑**」，按照以下步骤操作，大约 20 分钟即可完成全部部署。秒杀全链路（下单 → Redis 预扣 → MQ → 消费落库 → 取消归还 → 重试与死信）已在此形态下完整验证过。

> **提示**：所有中间件均通过 Docker 部署，请确保 Docker Desktop（或 Rancher Desktop / WSL2）已启动并正常运行。

## 1. 环境要求

| 软件 | 最低版本 | 验证命令 |
|------|---------|---------|
| JDK | 21+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Node.js | 18+ | `node -v` |
| Docker & Docker Compose | 最新版 | `docker --version` / `docker compose version` |
| Git | 最新版 | `git --version` |

> **提示**：JDK 推荐使用 Oracle JDK 21 或 Eclipse Temurin 21。如果使用 Maven Wrapper（`mvnw`），则无需单独安装 Maven。

## 2. 中间件部署（Docker Compose）

项目根目录已提供 docker-compose.yml，包含全部中间件（MySQL、Redis、Nacos、RocketMQ）以及后端/前端服务定义。

### 2.1 启动中间件

```bash
cd flash-sale
docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker
```

首次启动会拉取镜像，等待约 2-3 分钟。

> **本指南的用法无需任何额外变量**：`docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker` 与 `docker compose down` 开箱即用，docker profile 自带一把仅本地可用的默认签名 key（与 dev 同一把，开源 demo 的可接受边界）。真要部署到公网时换成 prod profile（`${JWT_SECRET}` 无默认值，缺失即启动失败），或在 compose 里显式注入 `- JWT_SECRET=${JWT_SECRET}`，值来自 `.env`（已被忽略）或平台密钥服务，参考 `.env.example`。
>
> **不要期待 `docker compose up -d` 全量启动可用**：该路径当前已知不完整（五处坑），逐项清单与绕法见[全量容器化部署](./full-container.md)。日常开发请按本页走。

### 2.2 验证中间件启动状态

```bash
# 查看所有容器状态
docker ps --format "table {{.Names}}\t{{.Status}}"

# 检查 RocketMQ Broker 是否就绪（必须看到 boot success）
docker logs flash-rocketmq-broker --tail 5

# 验证 MySQL 连接
docker exec flash-mysql mysql -u root -proot123 -e "SELECT VERSION();"

# 验证 Redis 连接
docker exec flash-redis redis-cli ping
```

**关键配置说明：**

| 中间件 | 容器名 | 地址 | 账号/密码 |
|--------|--------|------|-----------|
| MySQL 8.0 | flash-mysql | 127.0.0.1:3306 | root / root123 |
| Redis 7 | flash-redis | 127.0.0.1:6379 | 无密码 |
| Nacos 2.5.1 | flash-nacos | 127.0.0.1:8848 | nacos / nacos |
| RocketMQ 5.3.0 NameServer | flash-rocketmq-namesrv | 127.0.0.1:9876 | - |
| RocketMQ 5.3.0 Broker | flash-rocketmq-broker | 127.0.0.1:10911 | - |

> **说明**：`brokerIP1 = 127.0.0.1` 配置在 `docker/rocketmq/conf/broker.conf` 中，确保本地 Java 应用能直接连接 Broker，而非通过 Docker 内部 IP。
> **配置文件说明**：docker-compose 中后端服务通过 `SPRING_PROFILES_ACTIVE=docker` 启用 `application-docker.yml`（中间件地址使用容器名，如 `mysql` 而非 `127.0.0.1`）。本地手动开发时使用 `application-dev.yml`（`127.0.0.1` 直连）。

### 2.3 Nacos 管理员账号初始化（仅全新卷需要）

**只有全新卷才需要这一步**（首次启动、或 `docker compose down -v` 之后）。`application-dev.yml` 用的是 **public 命名空间**，所以**不需要创建任何命名空间**。

判断是否要初始化：后端启动报 `NacosException: user not found!`，或 `docker logs flash-nacos | grep "User nacos not found"` 命中，就是服务端还没有账号。

```bash
docker exec flash-nacos sh -c 'wget -q -O- -T 6 --post-data="password=nacos" http://127.0.0.1:8848/nacos/v1/auth/admin'
# 期望输出：{"username":"nacos","password":"nacos"}
```

**为什么用 `docker exec` 而不是开浏览器**：浏览器要走的 `127.0.0.1:8848` 是 Docker 发布的端口，在 Rancher Desktop / WSL2 下这条宿主转发链路会失效（连得上、拿不到响应），此时控制台打不开；容器内回环始终可达。控制台能打开时，直接访问 http://localhost:8848/nacos 按提示设置 `nacos / nacos` 效果相同。

> compose 已为 Nacos 挂了 `flash-nacos-data:/home/nacos/data`，普通的 `docker compose down` + `up` **不会再要求初始化**。但 `docker compose down -v` 会连卷一起删掉 —— 那之后就又是一次全新实例，需要重新执行上面那条命令。

### 2.4 启动监控栈（可选）

```bash
docker compose up -d prometheus grafana node-exporter sentinel-dashboard
```

监控链路的完整说明、关键配置与坑见[可观测性](../architecture/observability.md)。

### 2.5 中间件管理命令

```bash
# 停止所有中间件（保留数据卷）
docker compose stop mysql redis nacos rocketmq-namesrv rocketmq-broker

# 重新启动
docker compose start mysql redis nacos rocketmq-namesrv rocketmq-broker

# 完全删除容器（保留数据卷）
docker compose rm -f mysql redis nacos rocketmq-namesrv rocketmq-broker

# 完全删除容器+数据卷（所有数据丢失，谨慎使用）
docker compose down -v
```

## 3. 数据库初始化

```bash
# 数据在 flash-mysql-data 卷里，建过一次即可（down -v 之后要重来）
docker compose exec -T mysql mysql -uroot -proot123 flash_sale < sql/init.sql
```

该脚本会完成以下操作：

1. 创建 4 张核心表（user、item、flash_sale、flash_order）
2. 写入 7 件商品 + 7 个秒杀活动的种子数据

> **注意**：管理员账号（`admin/admin123`）不需要手动创建。应用首次启动时，DataInitRunner 会自动初始化管理员账号。
> **注意**：MySQL 内部执行 SQL 时如果字符集不匹配会导致中文乱码，必要时指定 `--default-character-set=utf8mb4`。

## 4. 启动后端服务

```bash
# 编译（跑全部后端测试；无 Docker 时真实 Redis 的集成测试整类跳过）
mvn clean verify

# 按顺序启动（网关最后）
java -jar flash-api/target/flash-api-1.0.0.jar
java -jar flash-admin/target/flash-admin-1.0.0.jar
java -jar flash-gateway/target/flash-gateway-1.0.0.jar
```

::: warning 启动顺序很关键
必须先启动业务服务（flash-api、flash-admin），最后启动网关（flash-gateway）。因为网关通过 Nacos 发现服务，如果网关先启动而业务服务尚未注册，路由将无法正常工作。
:::

| 服务 | 端口 | 职责 |
|------|------|------|
| flash-api | 8081 | 用户端 API（注册、登录、秒杀下单等），启用 MQ 消费者 |
| flash-admin | 8082 | 管理端 API（商品/活动/订单管理），不含 MQ 消费者隔离 |
| flash-gateway | 8080 | API 网关（路由转发、JWT 鉴权） |

> **提示**：在 IDEA 中开发时，可以直接运行各模块的 Spring Boot 主类，无需先打包。跑 `java -jar` 前一律 `mvn clean package`，避免 IDE 后台编译器往 `target/classes` 写坏 class。

## 5. 启动前端

```bash
# 用户端（5173）
cd flash-frontend && npm install && npm run dev

# 管理端（5174）
cd flash-admin-frontend && npm install && npm run dev
```

两个前端项目均配置了 Vite 开发代理，将 `/api`、`/admin` 请求转发到网关 http://localhost:8080，前端开发不需要关心跨域。前端启动前请确保网关已正常运行，否则 API 请求返回 502。更多见[前端开发](../development/frontend.md)。

## 6. 访问地址

| 服务 | 地址 | 备注 |
|------|------|------|
| API 网关 | http://localhost:8080 | 所有 API 请求的统一入口 |
| 用户端前端 | http://localhost:5173 | 用户秒杀页面 |
| 管理端前端 | http://localhost:5174 | 后台管理页面 |
| Nacos 控制台 | http://localhost:8848/nacos | 账号密码：`nacos/nacos`（需已完成 §2.3） |
| Prometheus | http://localhost:9090 | 指标查询与告警 |
| Grafana | http://localhost:3000 | 监控大盘（admin/admin） |
| Sentinel Dashboard | http://localhost:8718 | 流控/熔断规则（sentinel/sentinel） |

默认管理员账号：`admin` / `admin123`（由 `DataInitRunner` 首次启动自动创建）。

遇到问题请先查[常见问题排查](./troubleshooting.md)。
