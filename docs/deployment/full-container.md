# 全量容器化部署

::: tip 2026-10-06 起可用
原先「本项目未采用」的判断已更新：新增覆盖文件 `docker-compose.full.yml`，全栈可一条命令拉起并**已端到端验证**。日常开发仍推荐[本地开发部署](./local.md)（方式二），但全量模式不再是"保留着但不保证可用"的状态。
:::

## 一条命令

```bash
docker compose -f docker-compose.yml -f docker-compose.full.yml up -d
```

覆盖文件只替换与全量容器化互斥的三处配置，**方式二完全不受影响**（详见文件内注释）。

## 五道坎的现状

原 2026-09-19 记录的 5 处卡点，逐项状态如下：

| # | 卡点 | 现状（2026-10-06 复核） |
|---|------|------|
| 1 | Compose 自己构建镜像会崩掉整个命令（Compose v2.40.3 + containerd 镜像存储，bake 路径 panic，栈顶 `build_bake.go`） | **本次未复现**。`docker compose up -d --build` 正常完成 4 个后端镜像构建。该问题依赖 compose 版本与镜像存储后端组合，仍建议保留绕法：`COMPOSE_BAKE=false docker compose build api admin gateway` |
| 2 | broker 注册地址：`brokerIP1 = 127.0.0.1` 是给"后端跑宿主机"用的，全量容器下其他容器拿到的地址指向自己，消费者连不上 | **已修**：`docker-compose.full.yml` 把 broker 的 conf 换成 `docker/rocketmq/conf/broker-docker.conf`（`brokerIP1 = rocketmq-broker`）并把 `command` 指过去。实测 broker 日志为 `The broker[broker-a, rocketmq-broker:10911] boot success` |
| 3 | 前端容器不反代：`nginx:alpine` 默认只当静态文件处理，`/api/**` 与 `/admin/**` 全 404 | **已修**：`docker-compose.full.yml` 把 `docker/nginx/frontend.conf`、`admin-frontend.conf` 挂进两个前端容器。实测 `curl http://localhost:5173/api/flash-sale/active` → **HTTP 200** |
| 4 | Nacos 2.4+ 全新实例无用户，后端注册报 `user not found!` | **已修（两模式均生效）**：compose 挂 `flash-nacos-data:/home/nacos/data`；全新卷用一条 `docker exec` 设密码，见[本地部署 §2.3](./local.md) |
| 5 | 宿主侧端口转发在 Rancher Desktop / WSL2 下会整体性失效（某几个发布端口连得上拿不到响应，`--force-recreate` 无效，需重启容器运行时），容器间访问正常 | **仍未解决，属虚拟化层**。本次实测：Prometheus 容器内 `/-/healthy` 通过、`prometheus-docker.yml` 已加载，但宿主 `localhost:9090` 无监听，`--force-recreate` 后依旧。其余端口 8080/8081/8082/5173/5174/3000/8718/8848 均正常 |

## 端到端验证（2026-10-06 实测）

在本机（8 核 32G / Rancher Desktop）用上述命令起全栈后：

| 验证项 | 结果 |
|---|---|
| 容器 | 14/14 全部启动 |
| broker 注册地址 | `rocketmq-broker:10911`（容器内可解析） |
| 网关直连 | `GET http://localhost:8080/api/flash-sale/active` → 200 |
| 前端容器反代 | `GET http://localhost:5173/api/flash-sale/active` → **200**（前端 nginx → gateway → api 全链路通） |
| 两个前端页面 | `5173/`、`5174/` → 均 200 |
| 监控与注册中心 | Grafana `3000/api/health`、Nacos `8848/nacos/`、Sentinel `8718/` → 均 200 |
| **真实下单闭环** | **10/10 = 100%**（取图形验证码 → 下单 → 轮询 `messageKey` 至 `DONE`）；下单接口 P50 57.3ms / P95 370.0ms |
| Prometheus | 容器内健康，宿主 9090 不可达（卡点 5） |

> 下单闭环验证用脚本：[`scripts/wrk/order-e2e.ps1`](../../scripts/wrk/order-e2e.ps1)。同一用户在单个活动上有购买上限（`flash_sale.limit_per_user`），每轮必须换一个全新用户，否则会稳定拿到 `50002 已达到限购数量`。

## 命令顺序（含数据库初始化）

```bash
# 0) 数据在 flash-mysql-data 卷里；down -v 之后需要重新灌种子
docker compose -f docker-compose.yml -f docker-compose.full.yml up -d
docker compose exec -T mysql mysql -uroot -proot123 flash_sale < sql/init.sql

# 全新 Nacos 卷还要初始化一次管理员密码（命令见本地部署 §2.3），否则 api/admin 起不来
```

前端 `dist/` 需先构建（compose 把 `flash-frontend/dist`、`flash-admin-frontend/dist` 挂进 nginx）：

```bash
cd flash-frontend && npm install && npm run build && cd ..
cd flash-admin-frontend && npm install && npm run build && cd ..
```

## 与方式二的取舍

| | 方式二（中间件容器化 + 应用跑宿主机） | 方式一（全量容器化） |
|---|---|---|
| 改一行 Java 的代价 | 重启本地进程 | 重建镜像 |
| 调试 | 断点直连 | 需 attach |
| 一键复现 | 需按步骤起多项 | 一条命令 |
| 适用场景 | **日常开发（推荐）** | 演示、交接、验收前的一次性复核 |
