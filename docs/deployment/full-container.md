# 全量容器化部署（已知不完整，日常请勿使用）

::: danger 本页不是推荐形态
本项目的推荐形态是「中间件容器化 + 后端/前端跑宿主机」，见[本地开发部署](./local.md)。compose 文件里的后端/前端容器这一段保留着但**不保证可用**。以下是 2026-09-19 逐项实测的结论记录，免得再有人去趟。
:::

全量启动（`docker compose up -d` 把 api/admin/gateway/两个前端也拉起来）要过五道坎，最后一道（虚拟化层网络）不在仓库能解决的范围内：

| # | 卡点 | 现状 |
|---|------|------|
| 1 | Compose 自己构建镜像会**崩掉整个命令**：Docker Compose v2.40.3 + containerd 镜像存储下，bake 构建路径 panic（栈顶 `build_bake.go`）。绕法是先手工建镜像：`COMPOSE_BAKE=false docker compose build api admin gateway` | 未根治（该开关官方标 deprecated，长期做法是固定 compose 版本或用 `docker buildx build --target` 逐个打） |
| 2 | broker 注册地址：`docker/rocketmq/conf/broker.conf` 里 `brokerIP1 = 127.0.0.1` 是给「后端跑宿主机」用的，全量容器下其他容器拿到的 broker 地址指向自己 → 消费者连不上 | 绕法已备好但 **compose 未挂载**：`docker/rocketmq/conf/broker-docker.conf`（`brokerIP1 = rocketmq-broker`），要走全量需自行把 broker 服务的 conf 换成它 |
| 3 | 前端容器不反代：`nginx:alpine` 默认只当静态文件处理，`/api/**` 与 `/admin/**` 全 404（页面能打开、数据取不到） | 绕法已备好但 **compose 未挂载**：`docker/nginx/frontend.conf`、`admin-frontend.conf`（含 SPA `try_files` 与到 `gateway:8080` 的反代），要走全量需自行挂进两个前端服务 |
| 4 | Nacos 2.4+ 不再自带 `nacos/nacos`，全新实例没有用户，后端注册直接报 `user not found!`；原先没挂数据卷时每次 `down` 都要重新初始化 | 已修（这行对两种方式都生效）：compose 挂 `flash-nacos-data:/home/nacos/data`，普通 `down`/`up` 不再要求初始化；全新卷用一条 `docker exec` 命令设密码，见[本地部署 §2.3](./local.md) |
| 5 | 宿主侧端口转发在 Rancher Desktop / WSL2 下会整体性失效（表现为某几个发布端口连得上拿不到响应，`--force-recreate` 无效，需重启容器运行时），容器间访问同一端点正常 | **未解决**，属虚拟化层网络，不是仓库配置问题 |

仍要走全量部署，命令顺序是（前提：先把第 2、3 行的配置挂回去）：

```bash
cd flash-frontend && npm install && npm run build && cd ..
cd flash-admin-frontend && npm install && npm run build && cd ..
COMPOSE_BAKE=false docker compose build api admin gateway
docker compose up -d
docker compose exec -T mysql mysql -uroot -proot123 flash_sale < sql/init.sql
# 全新 Nacos 卷还要初始化一次管理员密码（命令见本地部署 §2.3），否则 api/admin 起不来
```

> 全量容器化后 Prometheus 抓取地址需从 `host.docker.internal:8081/8082` 改回容器名 `api:8081` / `admin:8082`，并放开 docker-compose.yml 中 api/admin 的宿主端口映射注释；改完 `docker/prometheus/prometheus.yml` 必须 `docker restart flash-prometheus`（挂载配置不热加载，否则大盘全部 No data）。详见[可观测性](../architecture/observability.md)。
