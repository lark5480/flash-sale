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

```bash
# 进入项目根目录
cd flash-sale

# 启动全部中间件（MySQL + Redis + Nacos + RocketMQ）
docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker
```

首次启动会拉取镜像，等待约 2-3 分钟。

> **本指南的用法（中间件容器化 + 后端跑宿主机）无需任何额外变量**：`docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker` 与 `docker compose down` 开箱即用，docker profile 自带一把仅本地可用的默认签名 key（与 dev 同一把，开源 demo 的可接受边界）。
> 真要部署时换成 prod profile（`${JWT_SECRET}` 无默认值，缺失即启动失败），或在 compose 里显式注入 `- JWT_SECRET=${JWT_SECRET}`，
> 值来自 `.env`（已被忽略）或平台密钥服务，参考 `.env.example`。
>
> **不要期待 `docker compose up -d` 全量启动可用**：该路径当前已知不完整（Compose bake 构建 panic、namesrv 容器网络黑盒等五处坑），逐项清单与绕法见 README「方式一」。日常开发请按本指南走：中间件容器 + 后端/前端在宿主机启动。

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

### 2.3 Nacos 管理员账号初始化

**只有全新卷才需要这一步**（首次启动、或 `docker compose down -v` 之后）。`application-dev.yml` 用的是 **public 命名空间**（代码里注释「使用默认 public 命名空间，重启不丢失」），所以**不需要创建任何命名空间**。

判断是否要初始化：后端启动报 `NacosException: user not found!`，或 `docker logs flash-nacos | grep "User nacos not found"` 命中，就是服务端还没有账号。

一条命令初始化（推荐，不依赖浏览器与宿主端口转发）：

```bash
docker exec flash-nacos sh -c 'wget -q -O- -T 6 --post-data="password=nacos" http://127.0.0.1:8848/nacos/v1/auth/admin'
# 期望输出：{"username":"nacos","password":"nacos"}
```

**为什么用 `docker exec` 而不是开浏览器**：浏览器要走的 `127.0.0.1:8848` 是 Docker 发布的端口，在 Rancher Desktop / WSL2 下这条宿主转发链路会失效（连得上、拿不到响应），此时控制台打不开、初始化也就无从下手；容器内回环始终可达。控制台能打开时，直接访问 http://localhost:8848/nacos 按提示设置 `nacos / nacos` 效果相同。

> compose 已为 Nacos 挂了 `flash-nacos-data:/home/nacos/data`（内嵌 Derby 的数据目录），普通的 `docker compose down` + `up` **不会再要求初始化**，命名空间与配置也不丢。
> 但 `docker compose down -v` 会连卷一起删掉 —— 那之后就又是一次全新实例，需要重新执行上面那条命令。本轮就踩过：09:38 清理残留时用了 `-v`，账号随卷消失。

### 2.4 启动监控栈

```bash
# 启动 Prometheus + Grafana + Node Exporter + Sentinel Dashboard
docker compose up -d prometheus grafana node-exporter sentinel-dashboard
```

| 服务 | 地址 | 账号/密码 |
|------|------|-----------|
| Prometheus | http://localhost:9090 | 无 |
| Grafana | http://localhost:3000 | admin / admin |
| Sentinel Dashboard | http://localhost:8718 | sentinel / sentinel |

> **说明**：Grafana 首次登录后数据源和看板会自动加载（Provisioning），无需手动配置。

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

### 3.1 创建数据库

```bash
docker exec flash-mysql mysql -u root -proot123 -e "CREATE DATABASE IF NOT EXISTS flash_sale DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"
```

### 3.2 初始化表结构和种子数据

```bash
docker exec -i flash-mysql mysql -u root -proot123 --default-character-set=utf8mb4 flash_sale < sql/init.sql
```

该脚本会完成以下操作：

1. 创建 4 张核心表（user、item、flash_sale、flash_order）
2. 写入 7 件商品 + 7 个秒杀活动的种子数据

> **注意**：管理员账号（`admin/admin123`）不需要手动创建。应用首次启动时，DataInitRunner 会自动初始化管理员账号。
> **注意**：MySQL 内部执行 SQL 时如果字符集不匹配会导致中文乱码，必须指定 --default-character-set=utf8mb4。

### 3.3 验证数据

```bash
docker exec -i flash-mysql mysql -u root -proot123 flash_sale -e "SELECT id,name FROM item;"
```

应返回 7 件商品，中文名称显示正常。

---

### 4.1 编译项目

在项目根目录执行 Maven 编译：

```bash
mvn clean verify
```

`verify` 会跑全部后端测试（与 CI 同一命令）；其中 `StockScriptRedisIntegrationTest` 需要 Docker，本机没有 Docker 时整类跳过。确实只想出包不想跑测试时用 `mvn clean package -DskipTests`。

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
| Prometheus | http://localhost:9090 | 指标查询与告警 |
| Grafana | http://localhost:3000 | 监控大盘（admin/admin） |
| Sentinel Dashboard | http://localhost:8718 | 流控/熔断规则（sentinel/sentinel） |

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

### 7.2 后端连不上 Nacos：先分清「没有账号」还是「链路不通」

dev 用 public 命名空间，**不需要创建命名空间**（prod 才通过 `${NACOS_NAMESPACE}` 指定）。注册失败按下面两步定位：

1. **服务端没有管理员账号** → 日志是 `NacosException: user not found!`。按 §2.3 那条 `docker exec` 命令初始化即可，不用开浏览器。
2. **宿主端口转发失效（Rancher Desktop / WSL2）** → 现象是连接能建立但一直不返回（`curl http://127.0.0.1:8848/...` 返回 000 或被重置），而同一批发布的其他端口（3306、3000、9090）正常。确认办法：

   ```bash
   # 容器内回环有响应 = Nacos 本身健康，问题在宿主转发链路
   docker exec flash-nacos sh -c 'wget -q -O- -T 5 http://127.0.0.1:8848/nacos/v1/console/server/state | head -c 60'
   ```

   走到这一步，`docker compose up -d --force-recreate nacos` 通常**不能**恢复（发布端口的转发生成在 Rancher 侧），需要重启容器运行时（Rancher 界面 Restart Container Runtime，或 `rdctl stop && rdctl start`），然后重新 `docker compose up -d <中间件>`。这与 §2.3 用 `docker exec` 初始化是同一个原因：能不依赖宿主端口的事，都别依赖它。

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
- [ ] Nacos 管理员账号已初始化（全新卷才需要，命令见 §2.3）
- [ ] RocketMQ NameServer 和 Broker 均运行正常
- [ ] flash-api（8081）启动成功，日志无报错
- [ ] flash-admin（8082）启动成功，日志无报错
- [ ] flash-gateway（8080）启动成功，日志无报错
- [ ] 用户端前端（5173）可正常访问
- [ ] 管理端前端（5174）可正常访问，使用 `admin/admin123` 登录
- [ ] Prometheus（9090）可访问，Targets 页面 flash-api/flash-admin 为 UP
- [ ] Grafana（3000）可访问，Dashboard 自动加载
- [ ] Sentinel Dashboard（8718）可访问，能看到 flash-api 应用
