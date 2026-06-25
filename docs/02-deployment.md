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

## 2. 中间件部署（Docker Compose）

项目根目录已提供 docker-compose.yml，包含全部中间件（MySQL、Redis、Nacos、RocketMQ）以及后端/前端服务定义。

### 2.1 启动中间件

`ash
# 进入项目根目录
cd flash-sale

# 启动全部中间件（MySQL + Redis + Nacos + RocketMQ）
docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker
`

首次启动会拉取镜像，等待约 2-3 分钟。

### 2.2 验证中间件启动状态

`ash
# 查看所有容器状态
docker ps --format "table {{.Names}}\t{{.Status}}"

# 检查 RocketMQ Broker 是否就绪（必须看到 boot success）
docker logs flash-rocketmq-broker --tail 5

# 验证 MySQL 连接
docker exec flash-mysql mysql -u root -proot123 -e "SELECT VERSION();"

# 验证 Redis 连接
docker exec flash-redis redis-cli ping
`

**关键配置说明：**

| 中间件 | 容器名 | 地址 | 账号/密码 |
|--------|--------|------|-----------|
| MySQL 8.0 | flash-mysql | 127.0.0.1:3306 | root / root123 |
| Redis 7 | flash-redis | 127.0.0.1:6379 | 无密码 |
| Nacos 2.5.1 | flash-nacos | 127.0.0.1:8848 | nacos / nacos |
| RocketMQ 5.3.0 NameServer | flash-rocketmq-namesrv | 127.0.0.1:9876 | - |
| RocketMQ 5.3.0 Broker | flash-rocketmq-broker | 127.0.0.1:10911 | - |

> **说明**：rokerIP1 = 127.0.0.1 配置在 docker/rocketmq/conf/broker.conf 中，确保本地 Java 应用能直接连接 Broker，而非通过 Docker 内部 IP。

### 2.3 Nacos 命名空间初始化

> **重要**：Nacos 启动后，需要手动创建一个命名空间，否则微服务无法注册。

**操作步骤：**
1. 访问 http://localhost:8848/nacos ，使用 
acos / nacos 登录
2. 进入左侧菜单「命名空间」页面
3. 点击「新建命名空间」
4. **命名空间 ID** 填写（必须与配置一致）：
   `
   4b56aa8f-8ca1-484a-9189-607d0fd733ab
   `
5. **命名空间名称** 可自定义，例如 lash-sale-dev
6. 点击「确定」完成创建

> **注意**：命名空间 ID 是在创建时指定的，之后无法修改。如果创建时未填写 ID，Nacos 会自动生成随机 ID，此时必须删除后重新创建。该 ID 与 pplication-dev.yml 中的 spring.cloud.nacos.discovery.namespace 配置一致。

### 2.4 中间件管理命令

`ash
# 停止所有中间件（保留数据卷）
docker compose stop mysql redis nacos rocketmq-namesrv rocketmq-broker

# 重新启动
docker compose start mysql redis nacos rocketmq-namesrv rocketmq-broker

# 完全删除容器（保留数据卷）
docker compose rm -f mysql redis nacos rocketmq-namesrv rocketmq-broker

# 完全删除容器+数据卷（所有数据丢失，谨慎使用）
docker compose down -v
`
## 3. 数据库初始化

### 3.1 创建数据库

`ash
docker exec flash-mysql mysql -u root -proot123 -e "CREATE DATABASE IF NOT EXISTS flash_sale DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
`

### 3.2 初始化表结构和种子数据

`ash
docker exec -i flash-mysql mysql -u root -proot123 --default-character-set=utf8mb4 flash_sale < sql/init.sql
`

该脚本会完成以下操作：

1. 创建 4 张核心表（user、item、lash_sale、lash_order）
2. 写入 7 件商品 + 7 个秒杀活动的种子数据

> **注意**：管理员账号（dmin/admin123）不需要手动创建。应用首次启动时，DataInitRunner 会自动初始化管理员账号。
> **注意**：MySQL 内部执行 SQL 时如果字符集不匹配会导致中文乱码，必须指定 --default-character-set=utf8mb4。

### 3.3 验证数据

`ash
docker exec -i flash-mysql mysql -u root -proot123 flash_sale -e "SELECT id,name FROM item;"
`

应返回 7 件商品，中文名称显示正常。
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
