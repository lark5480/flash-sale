# 项目规则（AI 助手专用）
以下是项目规范，请严格遵守。

## 项目结构（多模块 Maven 项目）
```
flash-sale (parent pom)
├── flash-common      — 公共模块：ResultVO、ResultCode、BaseEntity、GlobalExceptionHandler、BusinessException、JwtUtil、PasswordUtil、JacksonConfig、MyMetaObjectHandler、RedisConstants、RocketMQConstants、StatusEnum、annotation（RateLimit）
├── flash-model       — 数据模型：entity（User/Item/FlashSale/FlashOrder）、dto（LoginDTO/RegisterDTO）、vo（PageVO/UserVO/ItemVO/FlashSaleVO/FlashOrderVO/LoginVO）、enums（UserRoleEnum/FlashSaleStatusEnum/OrderStatusEnum）
├── flash-mapper      — MyBatis-Plus Mapper：UserMapper、ItemMapper、FlashSaleMapper、FlashOrderMapper
├── flash-service     — 业务层：service + service.impl、config（MyBatisPlusConfig、RedisConfig、DataInitRunner、AsyncConfig）、filter（JwtAuthenticationFilter）、interceptor（RateLimitInterceptor）、producer（FlashOrderProducer）、consumer（FlashOrderConsumer）、message（FlashOrderMessage）
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
11. 配置文件按环境拆分：application.yml（通用）+ application-dev.yml + application-prod.yml，生产敏感信息走环境变量

## 生成要求
1. 直接生成可运行的完整代码
2. 不写多余解释、不写废话
3. 自动匹配项目结构和模块依赖
4. 接口、参数、注释完整规范
5. 生成干净、优雅、可直接上线
6. 新增模块需在父 pom.xml 注册 `<module>`
7. 新增 Entity 放在 flash-model，Mapper 放在 flash-mapper，Service 放在 flash-service，Controller 按端放在 flash-api 或 flash-admin
