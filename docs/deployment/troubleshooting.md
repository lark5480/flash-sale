# 常见问题排查

## 1. 端口被占用

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

## 2. 后端连不上 Nacos：先分清「没有账号」还是「链路不通」

dev 用 public 命名空间，**不需要创建命名空间**（prod 才通过 `${NACOS_NAMESPACE}` 指定）。注册失败按下面两步定位：

1. **服务端没有管理员账号** → 日志是 `NacosException: user not found!`。按[本地开发部署 §2.3](./local.md#_2-3-nacos-管理员账号初始化仅全新卷需要) 那条 `docker exec` 命令初始化即可，不用开浏览器。
2. **宿主端口转发失效（Rancher Desktop / WSL2）** → 现象是连接能建立但一直不返回（`curl http://127.0.0.1:8848/...` 返回 000 或被重置），而同一批发布的其他端口（3306、3000、9090）正常。确认办法：

   ```bash
   # 容器内回环有响应 = Nacos 本身健康，问题在宿主转发链路
   docker exec flash-nacos sh -c 'wget -q -O- -T 5 http://127.0.0.1:8848/nacos/v1/console/server/state | head -c 60'
   ```

   走到这一步，`docker compose up -d --force-recreate nacos` 通常**不能**恢复（发布端口的转发生成在 Rancher 侧），需要重启容器运行时（Rancher 界面 Restart Container Runtime，或 `rdctl stop && rdctl start`），然后重新 `docker compose up -d <中间件>`。

## 3. RocketMQ 连接超时

应用启动后日志中出现 RocketMQ 连接超时或无法连接 Broker 的错误。

**排查步骤：**

1. 确认 NameServer 和 Broker 容器都在运行：`docker ps | grep rocketmq`
2. 查看 Broker 日志，确认是否启动成功：`docker logs flash-rocketmq-broker | tail -30`（必须看到 `boot success`）
3. 检查 `broker.conf` 中的 `brokerIP1` 是否正确设置为宿主机 IP
4. 确认应用的 `rocketmq.name-server` 配置指向 `127.0.0.1:9876`

> **提示**：5.3.0 版本已修复早期 5.1.x 的 StoreUtil Bug。如遇到连接问题，先确认 Broker 容器正常运行且日志输出 `boot success`。

## 4. MySQL 认证插件问题

MySQL 8.0 默认使用 `caching_sha2_password` 认证插件，部分旧版客户端或驱动可能不兼容。

compose 文件中已通过 `--default-authentication-plugin=mysql_native_password` 强制使用旧版认证插件。如果仍然遇到认证错误，可以手动修改用户认证方式：

```sql
ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'root123';
FLUSH PRIVILEGES;
```

## 5. Docker 内存不足

如果同时启动所有中间件，大约需要 2-3 GB 内存。请确保 Docker Desktop 分配了足够的内存：

- 打开 Docker Desktop 设置
- 进入 Resources 页面
- 将 Memory 设置为至少 4 GB

## 6. 前端 npm install 失败

```bash
# 清除缓存后重试
npm cache clean --force
rm -rf node_modules package-lock.json
npm install

# 或使用淘宝镜像源
npm install --registry=https://registry.npmmirror.com
```

## 7. Redis 连到了错误的实例（Windows 特有）

::: warning 必须用 docker-compose 的 `flash-redis` 容器
不要额外在 Windows 上安装 Redis 服务：本地 Redis 默认绑 `127.0.0.1:6379`，容器绑 `0.0.0.0:6379`，两者能同时"起来"，但连 `127.0.0.1` 时精确绑定优先 → 应用连的是本地 Redis，而 `docker exec flash-redis redis-cli` 查的是容器，排查缓存时会出现"明明没键"的假象。
已装的处理方式：`Stop-Service Redis` + `Set-Service Redis -StartupType Disabled`，然后 `docker compose restart redis`。
:::

---

## 附录：快速检查清单

部署完成后，逐项确认以下检查点：

- [ ] MySQL 运行正常，`flash_sale` 数据库和 4 张表已创建
- [ ] Redis 运行正常，`redis-cli ping` 返回 `PONG`
- [ ] Nacos 管理员账号已初始化（全新卷才需要，命令见[本地部署 §2.3](./local.md)）
- [ ] RocketMQ NameServer 和 Broker 均运行正常
- [ ] flash-api（8081）启动成功，日志无报错
- [ ] flash-admin（8082）启动成功，日志无报错
- [ ] flash-gateway（8080）启动成功，日志无报错
- [ ] 用户端前端（5173）可正常访问
- [ ] 管理端前端（5174）可正常访问，使用 `admin/admin123` 登录
- [ ] Prometheus（9090）可访问，Targets 页面 flash-api/flash-admin 为 UP
- [ ] Grafana（3000）可访问，Dashboard 自动加载
- [ ] Sentinel Dashboard（8718）可访问，能看到 flash-api 应用
