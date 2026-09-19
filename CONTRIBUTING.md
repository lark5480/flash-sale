# 贡献指南（Contributing）

感谢你有兴趣为 Flash Sale 秒杀系统做贡献。本文说明从本地跑起到提交 PR 的完整流程，请在提 PR 前通读。

> 面向 AI 编码工具的协作约定见仓库根 [`AGENTS.md`](AGENTS.md)；人类读者的编码规范同样以那份为准（见下文「代码规范」）。

## 目录

- [环境要求](#环境要求)
- [本地开发流程](#本地开发流程)
- [分支与提交策略](#分支与提交策略)
- [代码规范](#代码规范)
- [测试要求](#测试要求)
- [密钥门禁（三道闸）](#密钥门禁三道闸)
- [版本冻结策略](#版本冻结策略)
- [提交 PR 检查清单](#提交-pr-检查清单)

## 环境要求

| 软件 | 最低版本 | 验证命令 |
|------|---------|---------|
| JDK | 21+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Node.js | 18+ | `node -v` |
| Docker & Docker Compose | 最新版 | `docker compose version` |
| Git | 最新版 | `git --version` |

完整的环境搭建与中间件部署步骤见 [本地开发部署](docs/deployment/local.md)。

## 本地开发流程

本项目日常开发采用「**中间件容器化 + 后端/前端跑宿主机**」形态（全量容器化部署已知不完整，勿用，原因见[全量容器化部署](docs/deployment/full-container.md)）。

```bash
# 1. 启动中间件
docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker

# 2. 初始化数据库（建过一次即可，down -v 之后要重来）
docker compose exec -T mysql mysql -uroot -proot123 flash_sale < sql/init.sql

# 3. 编译并跑全部后端测试（与 CI 同一命令）
mvn clean verify

# 4. 按顺序启动后端（网关最后）
java -jar flash-api/target/flash-api-1.0.0.jar
java -jar flash-admin/target/flash-admin-1.0.0.jar
java -jar flash-gateway/target/flash-gateway-1.0.0.jar

# 5. 启动前端
cd flash-frontend && npm install && npm run dev
cd flash-admin-frontend && npm install && npm run dev
```

## 分支与提交策略

- **分支模型**：`master` 为稳定分支，`dev` 为集成分支。PR 的目标分支为 `master`。
- **触发方式**：向 `master` / `dev` 的 push 会触发 CI；针对 `master` 的 Pull Request 会触发 CI。
- **提交信息**：使用祈使句、说清「改了什么 + 为什么改」；一个提交只做一件事，避免把无关改动和功能修改混在一起。
- **不要顺手重构**：功能提交里不要夹带格式化、批量重命名等无关改动，会显著增大 review 成本。

## 代码规范

编码与数据库规范的**权威清单**在 [`AGENTS.md`](AGENTS.md) 的「代码规范」「数据库规范」两节，人类贡献者同样适用。要点提示：

- 遵循阿里巴巴 Java 开发手册；统一返回 `ResultVO<T>`。
- 依赖注入使用**构造器注入**（`private final` + 构造器），不用 `@Autowired` 字段注入。
- 日志使用 SLF4J；禁止 `*` 号导入；禁止 `select *`，用 LambdaQueryWrapper 构建查询。
- 实体继承 `BaseEntity`，手写 getter/setter，不使用 Lombok `@Data`。
- if/else/for/while 必须使用大括号，即使只有一行。

新增代码请放回对应模块：Entity → `flash-model`，Mapper → `flash-mapper`，Service → `flash-service`，Controller 按端 → `flash-api` 或 `flash-admin`。

## 测试要求

CI 执行 `mvn -B clean verify`（**不跳过测试**），因此：

- **任何改动都必须带可运行的测试**，否则 CI 直接红。
- 只测单个模块要带 `-am`（`mvn -o -pl flash-service -am clean test`），否则会按本地仓库旧 jar 解析兄弟模块并报 `NoSuchMethodError`。
- 只跑一个测试类时加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 库存 Lua 相关测试用 Testcontainers 起真实 Redis（`redis:7-alpine`）；本机无 Docker 时该整类会自动跳过。

## 密钥门禁（三道闸）

为防止凭据泄露，仓库设了三道闸，请在本地就启用前两道：

1. **本地 pre-commit 钩子**（每个克隆启用一次）：

   ```bash
   git config core.hooksPath scripts/git-hooks
   ```

   提交时用 gitleaks 扫描暂存区（无原生二进制时退回 Docker，两者都没有则只告警放行）。确认误报可临时 `git commit --no-verify`。

2. **CI `secret-scan` job**：用 gitleaks（`--redact`）扫描提交历史，作为兜底。
3. **`.gitignore`** 覆盖 `.env` 与压测产物。

> ⚠️ gitleaks 抓不到配置里的**低熵口令**，"扫描通过"不等于"仓库里没有明文密钥"。真实密钥请走 `.env`（参考 `.env.example`）或平台密钥服务，不要提交进仓库。

## 版本冻结策略

项目功能已完成，技术栈**有意冻结**：Spring Boot 3.2.0 / Spring Cloud 2023.0.0 / Spring Cloud Alibaba 2023.0.1.0 / Nacos v2.5.1。

- **请勿在 PR 中顺手升级框架 / 中间件版本**，修改 `pom.xml` 或 `docker-compose.yml` 时不要顺带 bump 版本号。
- Boot 3.2 虽已过 OSS EOL，但这是知情决策而非欠账；解冻升级需由维护者在明确场景下统一推进。

## 提交 PR 检查清单

- [ ] 本地 `mvn clean verify` 全绿（含新增/修改的测试）
- [ ] 已启用 pre-commit 钩子，提交无疑似密钥
- [ ] 改动符合 `AGENTS.md` 代码 / 数据库规范
- [ ] 未夹带无关的格式化 / 重构 / 版本升级改动
- [ ] 涉及部署或架构的改动，已同步更新 `docs/` 与 `README.md` 对应说明
- [ ] PR 描述说清了「改了什么 + 为什么改」
