# 项目规则（AI 助手专用）
以下是项目规范，请严格遵守。

## 项目结构（多模块 Maven 项目）
```
flash-sale (parent pom)
├── flash-common      — 公共模块：ResultVO、ResultCode、BaseEntity、GlobalExceptionHandler、BusinessException、JwtUtil、PasswordUtil、SnowflakeIdGenerator、JacksonConfig（Long→String 序列化）、MyMetaObjectHandler、RedisConstants、RocketMQConstants、StatusEnum、annotation（RateLimit）
├── flash-model       — 数据模型：entity（User/Item/FlashSale/FlashOrder）、dto（LoginDTO/RegisterDTO）、vo（PageVO/UserVO/ItemVO/FlashSaleVO/FlashOrderVO/LoginVO）、enums（UserRoleEnum/FlashSaleStatusEnum/OrderStatusEnum）
├── flash-mapper      — MyBatis-Plus Mapper：UserMapper、ItemMapper、FlashSaleMapper、FlashOrderMapper
├── flash-service     — 业务层：service + service.impl、config（MyBatisPlusConfig、RedisConfig、CacheConfig（Caffeine三级缓存）、IdGeneratorConfig（雪花ID Bean）、DataInitRunner、AsyncConfig（空，已迁至MQ））、filter（JwtAuthenticationFilter）、interceptor（RateLimitInterceptor）、producer（FlashOrderProducer）、consumer（FlashOrderConsumer）、message（FlashOrderMessage）
├── flash-api         — C 端 API 应用（端口独立）：AuthController、FlashOrderController、FlashSaleController、ItemController、CaptchaController + config（ApiSecurityConfig、WebMvcConfig）
├── flash-admin       — 后台管理应用（端口独立）：AuthController、FlashSaleController、ItemController、OrderController、UserController、CaptchaController + scheduler（FlashSaleScheduler、OrderScheduler）+ config（AdminSecurityConfig、WebMvcConfig）
├── flash-gateway     — Spring Cloud Gateway 网关：CorsConfig、AuthGlobalFilter
├── flash-frontend        — C 端前端（Vue 3）
└── flash-admin-frontend  — 后台前端（Vue 3 + Element Plus）
```

### 模块依赖链
```
flash-common → flash-model → flash-mapper → flash-service → flash-api / flash-admin
flash-gateway → flash-common（排除 web/tomcat/mybatis/validation 等重量依赖）
```

### 基础包路径
`com.flashsale.{模块}`

## 代码规范
1. 遵循阿里巴巴 Java 开发手册
2. 类名：大驼峰；方法/变量：小驼峰；常量：大写下划线
3. 统一返回 `ResultVO<T>`，成功用 `ResultVO.success(data)`，失败用 `ResultVO.fail(ResultCode.xxx)` 或 `ResultVO.fail(code, msg)`
4. 全局异常处理使用 `GlobalExceptionHandler`（@RestControllerAdvice），自定义异常：BusinessException、UnauthorizedException、ForbiddenException
5. 工具类放在 `flash-common` 的 `util` 包，类名使用单数（JwtUtil、PasswordUtil）
6. 实体继承 `BaseEntity`（提供 id、createTime、updateTime、isDeleted + @TableLogic）
7. **依赖注入使用构造器注入**（`private final` + 构造器），不使用 `@Autowired` 字段注入
8. **日志使用 SLF4J**：`private static final Logger log = LoggerFactory.getLogger(Xxx.class);`
9. 禁止 * 号导入
10. 禁止 select *，使用 LambdaQueryWrapper 构建查询
11. 逻辑删除字段：is_deleted（Integer 类型，0=未删除，1=已删除）
12. 实体类使用手动 getter/setter，不使用 Lombok @Data
13. util 和 enum 类必须有类级 Javadoc，枚举常量必须有注释说明业务含义
14. if/else/for/while 必须使用大括号，即使只有一行

## 数据库规范
字段使用小写+下划线，无 SQL 关键字，必须包含：
`id`（BIGINT，自增主键）、`create_time`（DATETIME）、`update_time`（DATETIME）、`is_deleted`（INT，逻辑删除，0/1）

## 架构要点
1. 秒杀下单走 RocketMQ 异步削峰（FlashOrderProducer → FlashOrderConsumer）
2. Redis 缓存预热：秒杀激活时写入库存 + 详情缓存；更新秒杀时自动删除缓存，下次请求重新加载
3. 分布式锁使用 Redisson（RLock）
4. 定时任务使用 @Scheduled（在 flash-admin 中，如 FlashSaleScheduler）
5. 服务发现使用 Nacos，网关使用 Spring Cloud Gateway
6. 认证使用 Spring Security + JWT，网关层 AuthGlobalFilter 做统一鉴权
7. 接口限流使用 @RateLimit 注解 + Redis ZSET 滑动窗口（RateLimitInterceptor），登录/注册/秒杀/管理端均有限流
8. 验证码使用 CaptchaService（算术题 + Redis 存储），登录和秒杀下单需校验
9. Consumer 异常处理：BusinessException（售罄）吞没不重试，系统异常 re-throw 触发 RocketMQ 重试
10. 多 DB 写操作方法必须使用 @Transactional(rollbackFor = Exception.class)
11. 消费者隔离：@ConditionalOnProperty(name="flash.flash.consumer.enabled")，flash-api 启用、flash-admin 禁用，避免同消费组冲突
12. 雪花 ID（SnowflakeIdGenerator）用于分布式订单 ID，JacksonConfig 注解驱动 Long→String 序列化解决 JS 精度丢失
13. 配置文件按环境拆分：application.yml（通用）+ application-dev.yml（本地开发）+ application-prod.yml（生产环境变量）+ application-docker.yml（Docker Compose 容器名访问）
14. 监控栈：Actuator + Micrometer + Prometheus + Grafana + Sentinel。★ 关键坑：P99 计算依赖 `_bucket` 指标，application.yml 必须配 `management.metrics.distribution.percentiles-histogram.http.server.requests: true`，否则 Grafana P99 面板 "No data"。自定义 Timer 必须加 `.publishPercentileHistogram()`。埋点在 Controller 层（用户调一次统计一次），不在 Consumer 层。★ Prometheus 抓取地址随部署方式切换，两者互斥（不可同一份配置通用）：
  - 本地开发（后端跑宿主机、中间件与监控容器化）：`host.docker.internal:8081` / `host.docker.internal:8082`
  - 全量容器化部署（后端在 Compose 内）：改回容器名 `api:8081` / `admin:8082`，并放开 docker-compose.yml 中 api/admin 的宿主端口映射注释
  - 改完 `docker/prometheus/prometheus.yml` 必须执行 `docker restart flash-prometheus`——挂载的配置文件不会热加载，不重启 Targets 会停留在旧配置（表现为大盘全部 No data）

## 生成要求
1. 直接生成可运行的完整代码
2. 不写多余解释、不写废话
3. 自动匹配项目结构和模块依赖
4. 接口、参数、注释完整规范
5. 生成干净、优雅、可直接上线
6. 新增模块需在父 pom.xml 注册 `<module>`
7. 新增 Entity 放在 flash-model，Mapper 放在 flash-mapper，Service 放在 flash-service，Controller 按端放在 flash-api 或 flash-admin

## 版本策略
1. 项目功能已完成，技术栈**有意冻结**：Spring Boot 3.2.0 / Spring Cloud 2023.0.0 / Spring Cloud Alibaba 2023.0.1.0 / Nacos v2.5.1（Boot 3.2 已于 2024-12 OSS EOL，属知情决策，非欠账）
2. 未经用户明确要求，**禁止升级框架/中间件版本**，修改 pom 或 docker-compose 时不得顺手 bump 版本号
3. 解冻条件（满足其一）：用户要求重启功能开发；项目需部署公网跑真实流量
4. 若解冻升级，参照姊妹项目 mall-consistency-lab 已验证组合：Boot 3.5.16 / Spring Cloud 2025.0.3 / SCA 2025.0.0.0 / Nacos v3.0.3 / MP 3.5.17。★ 关键坑：MP 3.5.9+ 将分页拦截器拆分到 mybatis-plus-jsqlparser，必须与 starter 成对引入，否则 MyBatisPlusConfig 分页运行时报错
