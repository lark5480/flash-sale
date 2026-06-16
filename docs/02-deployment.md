# Flash Sale 秒杀系统 -- 开发环境部署指南

本文档面向新加入的开发者，帮助你在本地快速搭建完整的开发环境。按照以下步骤操作，大约 20 分钟即可完成全部部署。

> **提示**：所有中间件均通过 Docker 部署，请确保 Docker Desktop 已启动并正常运行。

---

## 1. 环境要求

在开始之前，请确认本地已安装以下软件：

| 软件 | 最低版本 | 验证命令 |
|------|---------|---------|
| JDK | 21+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Node.js | 18+ | `node -v` |
| Docker & Docker Compose | 最新版 | `docker --version` / `docker compose version` |
| Git | 最新版 | `git --version` |

> **提示**：JDK 推荐使用 Oracle JDK 21 或 Eclipse Temurin 21。如果使用 Maven Wrapper（`mvnw`），则无需单独安装 Maven。

---

## 2. 中间件部署（Docker）

本项目依赖四个中间件：MySQL、Redis、Nacos、RocketMQ。你可以逐个启动，也可以跳到 [2.5 一键启动所有中间件](#25-一键启动所有中间件) 使用合并的 compose 文件。

建议在项目根目录外创建一个专用目录来存放 compose 文件，例如 `D:\dev-env\flash-sale\`，也可以直接放在项目的根目录下。

---

### 2.1 MySQL 8.0

创建 `docker-compose-mysql.yml`：

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: mysql
    restart: unless-stopped
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root123
      TZ: Asia/Shanghai
    volumes:
      - mysql-data:/var/lib/mysql
    command:
      - --default-authentication-plugin=mysql_native_password
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+08:00
      - --max-connections=200
      - --innodb-buffer-pool-size=256M

volumes:
  mysql-data:
```

**启动命令：**

```bash
docker compose -f docker-compose-mysql.yml up -d
```

**关键配置说明：**

| 配置项 | 值 | 说明 |
|--------|-----|------|
| root 密码 | `root123` | 与 application.yml 中配置的密码一致 |
| 字符集 | `utf8mb4 / utf8mb4_unicode_ci` | 支持完整的 Unicode 字符（包括 emoji） |
| 时区 | `+08:00` | 东八区，与业务代码保持一致 |
| 认证插件 | `mysql_native_password` | MySQL 8 默认使用 `caching_sha2_password`，切换为旧版插件以保证兼容性 |
| 最大连接数 | `200` | 开发环境足够使用 |
| InnoDB 缓冲池 | `256M` | 开发环境适当分配，避免占用过多内存 |

**验证连接：**

```bash
docker exec -it mysql mysql -u root -proot123 -e "SELECT VERSION();"
```

---

### 2.2 Redis 7

创建 `docker-compose-redis.yml`：

```yaml
services:
  redis:
    image: redis:7
    container_name: redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    environment:
      TZ: Asia/Shanghai
    volumes:
      - redis-data:/data
    command:
      - redis-server
      - --appendonly
      - "yes"
      - --maxmemory
      - 256mb
      - --maxmemory-policy
      - allkeys-lru

volumes:
  redis-data:
```

**启动命令：**

```bash
docker compose -f docker-compose-redis.yml up -d
```

**关键配置说明：**

| 配置项 | 值 | 说明 |
|--------|-----|------|
| AOF 持久化 | `appendonly yes` | 开启 AOF 日志，防止数据丢失 |
| 最大内存 | `256mb` | 开发环境限制，生产环境需根据业务调整 |
| 淘汰策略 | `allkeys-lru` | 内存不足时淘汰最近最少使用的 key，适合缓存场景 |

> **注意**：本项目未设置 Redis 密码，仅适用于本地开发环境。

**验证连接：**

```bash
docker exec -it redis redis-cli ping
# 应返回 PONG
```

---

### 2.3 Nacos v2.5.1

创建 `docker-compose-nacos.yml`：

```yaml
services:
  nacos:
    image: nacos/nacos-server:v2.5.1
    container_name: nacos
    restart: unless-stopped
    ports:
      - "8848:8848"
      - "9848:9848"
      - "9849:9849"
    environment:
      - MODE=standalone
      - PREFER_HOST_MODE=hostname
      - NACOS_AUTH_ENABLE=true
      - NACOS_AUTH_TOKEN=5raBdLZjA6h1Aynxe+kQNCJAKfVYI04ghX2OGyiPAorKvCrxtR8q8RXpvtNIif0s
      - NACOS_AUTH_IDENTITY_KEY=serverIdentity
      - NACOS_AUTH_IDENTITY_VALUE=dev-nacos-identity
      - TZ=Asia/Shanghai
      - JVM_XMS=512m
      - JVM_XMX=512m
      - JVM_XMN=256m
    volumes:
      - nacos-logs:/home/nacos/logs
      - nacos-data:/home/nacos/data

volumes:
  nacos-logs:
  nacos-data:
```

**启动命令：**

```bash
docker compose -f docker-compose-nacos.yml up -d
```

**关键配置说明：**

| 配置项 | 值 | 说明 |
|--------|-----|------|
| 运行模式 | `standalone` | 单机模式，开发环境无需集群 |
| 认证 | `NACOS_AUTH_ENABLE=true` | 开启认证，默认账号密码为 `nacos/nacos` |
| 控制台地址 | http://localhost:8848/nacos | 启动后访问此地址进入管理界面 |
| JVM 参数 | `Xms=512m, Xmx=512m, Xmn=256m` | 开发环境适当缩减内存分配 |

**端口说明：**

| 端口 | 用途 |
|------|------|
| `8848` | HTTP 控制台及 OpenAPI |
| `9848` | gRPC 客户端通信端口 |
| `9849` | gRPC 服务端通信端口 |

> **重要**：启动 Nacos 后，需要在 Nacos 控制台创建一个命名空间（Namespace），**命名空间 ID 必须为**：
>
> ```
> 4b56aa8f-8ca1-484a-9189-607d0fd733ab
> ```
>
> 此 ID 与项目 `application.yml` 中的 `spring.cloud.nacos.discovery.namespace` 配置一致。如果命名空间 ID 不匹配，所有微服务将无法注册到 Nacos。
>
> **操作步骤**：
> 1. 访问 http://localhost:8848/nacos ，使用 `nacos/nacos` 登录
> 2. 进入「命名空间」页面
> 3. 点击「新建命名空间」
> 4. 命名空间 ID 填写 `4b56aa8f-8ca1-484a-9189-607d0fd733ab`
> 5. 命名空间名称可自定义，例如 `flash-sale-dev`

---

### 2.4 RocketMQ 5.3.0

> **注意**：经实测，`apache/rocketmq:5.3.0` 运行正常。更早的 5.1.x 镜像存在 `StoreUtil NoClassDefFoundError` Bug，5.3.0 已修复。

**第一步：创建 broker 配置文件**

创建目录结构并创建 `conf/broker.conf`：

```
rocketmq/
├── conf/
│   └── broker.conf
├── docker-compose-rocketmq.yml
└── data/          (运行时自动生成)
```

`conf/broker.conf` 内容：

```properties
brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
autoCreateTopicEnable = true
autoCreateSubscriptionGroup = true
brokerIP1 = 127.0.0.1
```

**第二步：创建 `docker-compose-rocketmq.yml`**

```yaml
services:
  namesrv:
    image: apache/rocketmq:5.3.0
    container_name: rocketmq-namesrv
    restart: unless-stopped
    ports:
      - "9876:9876"
    environment:
      TZ: Asia/Shanghai
      JAVA_OPT_EXT: -server -Xms256m -Xmx256m
    command: sh mqnamesrv
    volumes:
      - ./data/namesrv-logs:/home/rocketmq/logs

  broker:
    image: apache/rocketmq:5.3.0
    container_name: rocketmq-broker
    restart: unless-stopped
    ports:
      - "10909:10909"
      - "10911:10911"
    environment:
      TZ: Asia/Shanghai
      NAMESRV_ADDR: namesrv:9876
      JAVA_OPT_EXT: >-
        -server -Xms512m -Xmx512m -Xmn256m
    command: sh mqbroker -n namesrv:9876 -c /home/rocketmq/conf/broker.conf
    depends_on:
      - namesrv
    volumes:
      - ./conf/broker.conf:/home/rocketmq/conf/broker.conf
      - ./data/broker-logs:/home/rocketmq/logs
      - ./data/broker-store:/home/rocketmq/store
```

**启动命令：**

```bash
docker compose -f docker-compose-rocketmq.yml up -d
```

**关键配置说明：**

| 配置项 | 值 | 说明 |
|--------|-----|------|
| 镜像版本 | `5.3.0` | 5.3.0 已修复 StoreUtil Bug，更早的 5.1.x 版本存在该问题 |
| `autoCreateTopicEnable` | `true` | 自动创建 Topic，开发环境无需手动创建 |
| `autoCreateSubscriptionGroup` | `true` | 自动创建消费者组 |
| `brokerIP1` | `127.0.0.1` | Broker 对外暴露的 IP，必须设置为宿主机 IP。如果 Docker 运行在远程服务器，需改为服务器的公网/内网 IP |
| `flushDiskType` | `ASYNC_FLUSH` | 异步刷盘，开发环境追求性能 |
| `brokerRole` | `ASYNC_MASTER` | 异步主从，开发环境无需配置 Slave |

**端口说明：**

| 端口 | 用途 |
|------|------|
| `9876` | NameServer 端口（客户端通过此端口发现 Broker） |
| `10911` | Broker 主端口（消息收发） |
| `10909` | Broker VIP 通道端口 |

**验证运行状态：**

```bash
docker logs rocketmq-broker | tail -20
# 看到 "The broker[broker-a, 172.x.x.x:10911] boot success" 即为启动成功
```

> **重要**：确认 Broker 日志输出 `boot success` 后，再启动 flash-api 和 flash-admin。如果消费者在 Broker 就绪前启动，可能不报错但实际未订阅成功，消息将被静默丢弃。

---

### 2.5 一键启动所有中间件

如果你不想逐个启动，可以创建一个合并的 `docker-compose.yml`，一次启动所有中间件。

创建 `docker-compose.yml`：

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: mysql
    restart: unless-stopped
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root123
      TZ: Asia/Shanghai
    volumes:
      - mysql-data:/var/lib/mysql
    command:
      - --default-authentication-plugin=mysql_native_password
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+08:00
      - --max-connections=200
      - --innodb-buffer-pool-size=256M

  redis:
    image: redis:7
    container_name: redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    environment:
      TZ: Asia/Shanghai
    volumes:
      - redis-data:/data
    command:
      - redis-server
      - --appendonly
      - "yes"
      - --maxmemory
      - 256mb
      - --maxmemory-policy
      - allkeys-lru

  nacos:
    image: nacos/nacos-server:v2.5.1
    container_name: nacos
    restart: unless-stopped
    ports:
      - "8848:8848"
      - "9848:9848"
      - "9849:9849"
    environment:
      - MODE=standalone
      - PREFER_HOST_MODE=hostname
      - NACOS_AUTH_ENABLE=true
      - NACOS_AUTH_TOKEN=5raBdLZjA6h1Aynxe+kQNCJAKfVYI04ghX2OGyiPAorKvCrxtR8q8RXpvtNIif0s
      - NACOS_AUTH_IDENTITY_KEY=serverIdentity
      - NACOS_AUTH_IDENTITY_VALUE=dev-nacos-identity
      - TZ=Asia/Shanghai
      - JVM_XMS=512m
      - JVM_XMX=512m
      - JVM_XMN=256m
    volumes:
      - nacos-logs:/home/nacos/logs
      - nacos-data:/home/nacos/data

  namesrv:
    image: apache/rocketmq:5.3.0
    container_name: rocketmq-namesrv
    restart: unless-stopped
    ports:
      - "9876:9876"
    environment:
      TZ: Asia/Shanghai
      JAVA_OPT_EXT: -server -Xms256m -Xmx256m
    command: sh mqnamesrv
    volumes:
      - ./data/namesrv-logs:/home/rocketmq/logs

  broker:
    image: apache/rocketmq:5.3.0
    container_name: rocketmq-broker
    restart: unless-stopped
    ports:
      - "10909:10909"
      - "10911:10911"
    environment:
      TZ: Asia/Shanghai
      NAMESRV_ADDR: namesrv:9876
      JAVA_OPT_EXT: >-
        -server -Xms512m -Xmx512m -Xmn256m
    command: sh mqbroker -n namesrv:9876 -c /home/rocketmq/conf/broker.conf
    depends_on:
      - namesrv
    volumes:
      - ./conf/broker.conf:/home/rocketmq/conf/broker.conf
      - ./data/broker-logs:/home/rocketmq/logs
      - ./data/broker-store:/home/rocketmq/store

volumes:
  mysql-data:
  redis-data:
  nacos-logs:
  nacos-data:
```

**一键启动：**

```bash
docker compose up -d
```

**查看所有容器状态：**

```bash
docker compose ps
```

**一键停止所有中间件：**

```bash
docker compose down
```

> **提示**：首次启动需要拉取镜像，可能需要几分钟。后续启动会直接使用本地缓存的镜像，速度很快。

---

## 3. 数据库初始化

中间件启动后，执行以下命令初始化数据库和表结构：

```bash
mysql -u root -proot123 < sql/init.sql
```

该脚本会完成以下操作：

1. 创建数据库 `flash_sale`（字符集 utf8mb4）
2. 创建 4 张核心表（`user`、`item`、`flash_sale`、`flash_order`）
3. 写入 7 件商品 + 7 个秒杀活动的种子数据

> **注意**：管理员账号（`admin/admin123`）不需要手动创建。应用首次启动时，`DataInitRunner` 会自动初始化管理员账号。

**验证数据库：**

```bash
mysql -u root -proot123 -e "USE flash_sale; SHOW TABLES;"
```

应输出 4 张表：`flash_order`、`flash_sale`、`item`、`user`。

---

## 4. 后端启动

### 4.1 编译项目

在项目根目录执行 Maven 编译：

```bash
mvn clean package -DskipTests
```

编译完成后，各可启动模块的 JAR 包位于对应模块的 `target/` 目录下。

### 4.2 启动顺序

> **重要**：启动顺序很关键。必须先启动业务服务（flash-api、flash-admin），最后启动网关（flash-gateway）。因为网关通过 Nacos 发现服务，如果网关先启动而业务服务尚未注册，路由将无法正常工作。

**第一步：启动 flash-api（用户服务，端口 8081）**

```bash
java -jar flash-api/target/flash-api-1.0.0.jar
```

**第二步：启动 flash-admin（管理服务，端口 8082）**

```bash
java -jar flash-admin/target/flash-admin-1.0.0.jar
```

**第三步：启动 flash-gateway（网关服务，端口 8080）**

```bash
java -jar flash-gateway/target/flash-gateway-1.0.0.jar
```

### 4.3 服务端口一览

| 服务 | 端口 | 职责 |
|------|------|------|
| flash-api | 8081 | 用户端 API（注册、登录、秒杀下单等） |
| flash-admin | 8082 | 管理端 API（商品管理、活动管理、订单管理等） |
| flash-gateway | 8080 | API 网关（路由转发、JWT 鉴权） |

### 4.4 网关路由规则

| 路径前缀 | 转发目标 |
|----------|---------|
| `/api/**` | flash-api (8081) |
| `/admin/**` | flash-admin (8082) |

> **提示**：在 IDEA 中开发时，可以直接运行各模块的 Spring Boot 主类，无需先打包。

---

## 5. 前端启动

本项目有两个前端项目，均基于 Vue 3 + Vite 构建。

### 5.1 用户端（端口 5173）

```bash
cd flash-frontend
npm install
npm run dev
```

启动后访问 http://localhost:5173 。

### 5.2 管理端（端口 5174）

```bash
cd flash-admin-frontend
npm install
npm run dev
```

启动后访问 http://localhost:5174 。

### 5.3 Vite 代理说明

两个前端项目均配置了 Vite 开发代理，将 API 请求转发到网关：

| 前端项目 | 代理路径 | 转发目标 |
|----------|---------|---------|
| flash-frontend (5173) | `/api/**`、`/admin/**` | http://localhost:8080 |
| flash-admin-frontend (5174) | `/admin/**` | http://localhost:8080 |

这意味着前端开发时不需要关心跨域问题，Vite 会自动将 `/api` 和 `/admin` 开头的请求代理到网关服务。

> **提示**：前端启动前请确保网关（flash-gateway）已正常运行，否则 API 请求将返回 502 错误。

---

## 6. 访问地址

所有服务启动后，通过以下地址访问：

| 服务 | 地址 | 备注 |
|------|------|------|
| API 网关 | http://localhost:8080 | 所有 API 请求的统一入口 |
| 用户端前端 | http://localhost:5173 | 用户秒杀页面 |
| 管理端前端 | http://localhost:5174 | 后台管理页面 |
| Nacos 控制台 | http://localhost:8848/nacos | 账号密码：`nacos/nacos` |

---

## 7. 常见问题排查

### 7.1 端口被占用

启动服务时报 `Address already in use` 错误：

**Windows：**

```bash
netstat -ano | findstr :8080
# 找到占用端口的 PID，然后终止进程
taskkill /PID <PID号> /F
```

**Linux / macOS：**

```bash
lsof -i :8080
kill -9 <PID号>
```

> **提示**：常见的端口冲突包括 MySQL（3306）、Redis（6379）、Nacos（8848）。如果本地已经安装了这些服务，需要先停止本地服务或修改端口映射。

### 7.2 Nacos 命名空间 ID 不匹配

微服务启动后无法注册到 Nacos，日志中持续报错。

**排查步骤：**

1. 登录 Nacos 控制台 http://localhost:8848/nacos
2. 进入「命名空间」页面
3. 确认存在 ID 为 `4b56aa8f-8ca1-484a-9189-607d0fd733ab` 的命名空间
4. 如果不存在或 ID 不同，需要新建一个命名空间并指定该 ID

> **注意**：命名空间 ID 是在创建时指定的，之后无法修改。如果创建时没有指定 ID，Nacos 会自动生成一个随机 ID，此时只能删除后重新创建。

### 7.3 RocketMQ 连接超时

应用启动后日志中出现 RocketMQ 连接超时或无法连接 Broker 的错误。

**排查步骤：**

1. 确认 NameServer 和 Broker 容器都在运行：
   ```bash
   docker ps | grep rocketmq
   ```
2. 查看 Broker 日志，确认是否启动成功：
   ```bash
   docker logs rocketmq-broker | tail -30
   ```
3. 检查 `broker.conf` 中的 `brokerIP1` 是否正确设置为宿主机 IP
4. 确认应用的 `rocketmq.name-server` 配置指向 `127.0.0.1:9876`

> **提示**：如遇到 RocketMQ 连接问题，先确认 Broker 容器正常运行且日志输出 `boot success`。5.3.0 版本已修复早期 5.1.x 的 StoreUtil Bug。

### 7.4 MySQL 认证插件问题

MySQL 8.0 默认使用 `caching_sha2_password` 认证插件，部分旧版客户端或驱动可能不兼容。

compose 文件中已通过 `--default-authentication-plugin=mysql_native_password` 强制使用旧版认证插件。如果仍然遇到认证错误，可以手动修改用户认证方式：

```sql
ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'root123';
FLUSH PRIVILEGES;
```

### 7.5 Docker 内存不足

如果同时启动所有中间件，大约需要 2-3 GB 内存。请确保 Docker Desktop 分配了足够的内存：

- 打开 Docker Desktop 设置
- 进入 Resources 页面
- 将 Memory 设置为至少 4 GB

### 7.6 前端 npm install 失败

如果 `npm install` 报错，尝试以下方法：

```bash
# 清除缓存后重试
npm cache clean --force
rm -rf node_modules package-lock.json
npm install

# 或使用淘宝镜像源
npm install --registry=https://registry.npmmirror.com
```

---

## 附录：快速检查清单

部署完成后，逐项确认以下检查点：

- [ ] MySQL 运行正常，`flash_sale` 数据库和 4 张表已创建
- [ ] Redis 运行正常，`redis-cli ping` 返回 `PONG`
- [ ] Nacos 控制台可访问，命名空间 `4b56aa8f-8ca1-484a-9189-607d0fd733ab` 已创建
- [ ] RocketMQ NameServer 和 Broker 均运行正常
- [ ] flash-api（8081）启动成功，日志无报错
- [ ] flash-admin（8082）启动成功，日志无报错
- [ ] flash-gateway（8080）启动成功，日志无报错
- [ ] 用户端前端（5173）可正常访问
- [ ] 管理端前端（5174）可正常访问，使用 `admin/admin123` 登录
