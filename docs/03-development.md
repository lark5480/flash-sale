# Flash Sale 秒杀系统 - 开发指南

本文档帮助新加入的开发者快速理解项目结构、技术选型和开发规范，并尽快进入开发状态。

---

## 1. 项目模块依赖关系

本项目采用 Maven 多模块结构，基于 Spring Boot 3.2 + Spring Cloud 2023 + Spring Cloud Alibaba 构建。各模块之间的依赖关系如下：

```mermaid
graph TD
    flash-common[flash-common<br/>公共基础模块]
    flash-model[flash-model<br/>数据模型模块]
    flash-mapper[flash-mapper<br/>持久层模块]
    flash-service[flash-service<br/>业务逻辑模块]
    flash-api[flash-api<br/>用户端 API<br/>端口 8081]
    flash-admin[flash-admin<br/>管理端 API<br/>端口 8082]
    flash-gateway[flash-gateway<br/>API 网关<br/>端口 8080]

    flash-model --> flash-common
    flash-mapper --> flash-model
    flash-service --> flash-mapper
    flash-api --> flash-service
    flash-admin --> flash-service
    flash-gateway --> flash-common
```

**依赖说明：**

| 模块 | 依赖 | 说明 |
|------|------|------|
| flash-common | 无 | 公共工具类、异常处理、统一返回、常量定义 |
| flash-model | flash-common | 实体类、DTO、VO、枚举 |
| flash-mapper | flash-model | MyBatis-Plus Mapper 接口 |
| flash-service | flash-mapper | 业务逻辑实现、Redis/MQ 配置、消息生产与消费 |
| flash-api | flash-service | 用户端 REST 控制器，同时承载 MQ 消费者 |
| flash-admin | flash-service | 管理端 REST 控制器、定时任务调度器 |
| flash-gateway | flash-common | 仅依赖 JwtUtil 做 Token 校验，不依赖业务层 |

**端口分配：**

| 服务 | 端口 |
|------|------|
| flash-gateway | 8080 |
| flash-api | 8081 |
| flash-admin | 8082 |
| flash-frontend（用户前端） | 5173 |
| flash-admin-frontend（管理前端） | 5174 |

**中间件依赖：**

| 中间件 | 用途 | 配置地址 |
|--------|------|----------|
| MySQL 8.0 | 持久化存储 | 127.0.0.1:3306，数据库 `flash_sale` |
| Redis | 库存预扣、缓存、分布式锁、幂等校验 | 127.0.0.1:6379 |
| RocketMQ | 异步下单消息队列 | 127.0.0.1:9876 |
| Nacos | 服务注册与发现 | 127.0.0.1:8848 |

---

## 2. 包结构规范

```
com.flashsale
├── common                          # flash-common 模块
│   ├── result                      # 统一返回结果
│   │   ├── ResultVO                # 统一返回包装类
│   │   └── ResultCode              # 错误码枚举
│   ├── exception                   # 自定义异常
│   │   ├── BusinessException       # 业务异常
│   │   ├── UnauthorizedException   # 未授权异常
│   │   └── ForbiddenException      # 禁止访问异常
│   ├── handler
│   │   └── GlobalExceptionHandler  # 全局异常处理器
│   ├── util                        # 工具类
│   │   ├── JwtUtil                 # JWT 令牌工具
│   │   └── PasswordUtil            # 密码加密工具
│   ├── config
│   │   ├── MyMetaObjectHandler     # MyBatis-Plus 自动填充处理器
│   │   └── JacksonConfig           # Jackson JSON 序列化配置
│   ├── constant                    # 常量定义
│   │   ├── RedisConstants          # Redis Key 与 TTL 常量
│   │   └── RocketMQConstants       # RocketMQ Topic/Tag/Group 常量
│   ├── annotation
│   │   └── RateLimit               # 接口限流注解
│   └── enums
│       └── StatusEnum              # 通用状态枚举
│
├── model                           # flash-model 模块
│   ├── entity                      # 数据库实体
│   │   ├── User                    # 用户
│   │   ├── Item                    # 商品
│   │   ├── FlashSale               # 秒杀活动
│   │   └── FlashOrder              # 秒杀订单
│   ├── dto                         # 请求数据传输对象
│   │   ├── LoginDTO                # 登录请求
│   │   └── RegisterDTO             # 注册请求
│   ├── vo                          # 返回视图对象
│   │   ├── LoginVO                 # 登录返回（含 Token）
│   │   ├── UserVO                  # 用户信息
│   │   ├── ItemVO                  # 商品信息
│   │   ├── FlashSaleVO             # 秒杀活动详情（含商品信息）
│   │   ├── FlashOrderVO            # 秒杀订单（含 messageKey）
│   │   └── PageVO                  # 分页封装
│   └── enums                       # 业务枚举
│       ├── FlashSaleStatusEnum     # 秒杀活动状态
│       ├── OrderStatusEnum         # 订单状态
│       └── UserRoleEnum            # 用户角色
│
├── mapper                          # flash-mapper 模块
│   ├── UserMapper                  # 用户 Mapper
│   ├── ItemMapper                  # 商品 Mapper
│   ├── FlashOrderMapper            # 订单 Mapper
│   └── FlashSaleMapper             # 秒杀活动 Mapper
│
├── service                         # flash-service 模块
│   ├── UserService / ItemService / FlashSaleService / FlashOrderService
│   ├── impl                        # Service 实现类
│   │   ├── UserServiceImpl
│   │   ├── ItemServiceImpl
│   │   ├── FlashSaleServiceImpl
│   │   └── FlashOrderServiceImpl
│   ├── config                      # 配置类
│   │   ├── RedisConfig             # RedisTemplate + Lua 脚本加载
│   │   ├── MyBatisPlusConfig       # 分页插件配置
│   │   ├── AsyncConfig             # 异步线程池配置
│   │   └── DataInitRunner          # 启动数据初始化
│   ├── filter
│   │   └── JwtAuthenticationFilter # Spring Security JWT 过滤器
│   ├── interceptor
│   │   └── RateLimitInterceptor    # 接口限流拦截器（Redis 滑动窗口）
│   ├── producer
│   │   └── FlashOrderProducer      # RocketMQ 消息生产者
│   ├── consumer
│   │   └── FlashOrderConsumer      # RocketMQ 消息消费者（仅 flash-api 启用）
│   └── message
│       └── FlashOrderMessage       # MQ 消息体定义
│
├── api                             # flash-api 模块
│   ├── controller
│   │   ├── AuthController          # /api/auth/**（注册、登录、刷新 Token、验证码）
│   │   ├── CaptchaController       # /api/auth/captcha（生成算术验证码）
│   │   ├── ItemController          # /api/item/**（商品列表、详情）
│   │   ├── FlashSaleController     # /api/flash-sale/**（活动列表、详情）
│   │   └── FlashOrderController    # /api/order/**（下单、支付、取消、退款、删除、状态轮询）
│   └── config
│       ├── ApiSecurityConfig       # C 端 Spring Security 配置
│       └── WebMvcConfig            # 注册 RateLimitInterceptor
│
├── admin                           # flash-admin 模块
│   ├── controller
│   │   ├── AuthController          # /admin/auth/**（管理员登录，需验证码）
│   │   ├── CaptchaController       # /admin/auth/captcha（生成算术验证码）
│   │   ├── ItemController          # /admin/item/**（商品 CRUD）
│   │   ├── FlashSaleController     # /admin/flash-sale/**（活动 CRUD + 状态变更）
│   │   ├── OrderController         # /admin/order/**（订单查看、支付、退款、删除）
│   │   └── UserController          # /admin/user/**（用户管理，分页）
│   ├── config
│   │   ├── AdminSecurityConfig     # B 端 Spring Security 配置（仅 ADMIN 角色）
│   │   └── WebMvcConfig            # 注册 RateLimitInterceptor
│   └── scheduler
│       ├── FlashSaleScheduler      # 秒杀活动状态自动流转（每分钟）
│       └── OrderScheduler          # 超时未支付订单自动取消（每 5 分钟）
│
└── gateway                         # flash-gateway 模块
    ├── GatewayApplication          # 网关启动类
    ├── filter
    │   └── AuthGlobalFilter        # JWT 全局鉴权过滤器
    └── config
        └── CorsConfig              # 跨域配置
```

---

## 3. API 接口文档

### 3.1 用户端 API

通过 Gateway（端口 8080）按路径 `/api/**` 转发到 flash-api（端口 8081）。

| Method | Path | 说明 | 需要认证 |
|--------|------|------|----------|
| POST | `/api/auth/register` | 用户注册 | 否 |
| POST | `/api/auth/login` | 用户登录（需验证码），返回 accessToken 和 refreshToken | 否 |
| POST | `/api/auth/refresh?refreshToken=` | 刷新 Token | 否 |
| GET | `/api/auth/captcha` | 获取算术验证码（返回 captchaId + 表达式） | 否 |
| GET | `/api/item/list?page=1&size=10` | 商品列表（分页） | 是 |
| GET | `/api/item/{id}` | 商品详情 | 是 |
| GET | `/api/flash-sale/active` | 当前进行中的秒杀活动列表 | 是 |
| GET | `/api/flash-sale/{id}` | 秒杀活动详情（含关联商品信息） | 是 |
| POST | `/api/flash-sale/{id}/purchase` | 秒杀下单（需验证码），返回 messageKey | 是 |
| GET | `/api/order/status?messageKey=` | 轮询订单异步处理状态（PROCESSING / DONE） | 是 |
| GET | `/api/order/list?page=1&size=10` | 我的订单列表（分页） | 是 |
| GET | `/api/order/{id}` | 订单详情 | 是 |
| POST | `/api/order/{id}/pay` | 支付订单 | 是 |
| POST | `/api/order/{id}/cancel` | 取消订单 | 是 |
| POST | `/api/order/{id}/refund` | 退款 | 是 |
| DELETE | `/api/order/{id}` | 删除已取消订单 | 是 |

**秒杀下单流程：**

1. 客户端调用 `POST /api/flash-sale/{id}/purchase`
2. 服务端执行 Redis Lua 脚本进行库存预扣和限购校验
3. 预扣成功后，通过 RocketMQ 发送异步下单消息
4. 立即返回 `messageKey` 给客户端
5. 客户端使用 `messageKey` 轮询 `GET /api/order/status` 获取订单创建结果
6. 消费者异步处理：幂等校验 -> 分布式锁 -> DB 乐观锁扣库存 -> 创建订单

### 3.2 管理端 API

通过 Gateway（端口 8080）按路径 `/admin/**` 转发到 flash-admin（端口 8082）。

| Method | Path | 说明 |
|--------|------|------|
| POST | `/admin/auth/login` | 管理员登录（需验证码，校验 role=ADMIN） |
| GET | `/admin/auth/captcha` | 获取算术验证码 |
| GET | `/admin/item/list?page=1&size=10` | 商品列表（分页） |
| GET | `/admin/item/{id}` | 商品详情 |
| POST | `/admin/item` | 创建商品 |
| PUT | `/admin/item` | 更新商品（请求体中包含 id） |
| DELETE | `/admin/item/{id}` | 删除商品（逻辑删除） |
| GET | `/admin/flash-sale/list?page=1&size=10&status=` | 秒杀活动列表（分页，可按状态筛选） |
| GET | `/admin/flash-sale/{id}` | 秒杀活动详情 |
| POST | `/admin/flash-sale` | 创建秒杀活动 |
| PUT | `/admin/flash-sale` | 更新秒杀活动（请求体中包含 id） |
| DELETE | `/admin/flash-sale/{id}` | 删除秒杀活动（逻辑删除） |
| PUT | `/admin/flash-sale/{id}/status` | 变更活动状态（含 Redis 缓存预热） |
| GET | `/admin/order/list?page=1&size=10` | 订单列表（分页） |
| GET | `/admin/order/{id}` | 订单详情 |
| POST | `/admin/order/{id}/pay` | 确认支付 |
| POST | `/admin/order/{id}/refund` | 退款 |
| DELETE | `/admin/order/{id}` | 删除已取消订单 |
| GET | `/admin/user/list?page=1&size=10` | 用户列表 |
| PUT | `/admin/user/{id}/status` | 启用/禁用用户 |

### 3.3 认证机制

**Gateway 层鉴权（`AuthGlobalFilter`）：**

- 以下路径跳过鉴权：`/api/auth/register`、`/api/auth/login`、`/api/auth/refresh`、`/admin/auth/login`
- 其他路径必须在 `Authorization` 请求头中携带 `Bearer {token}`
- Token 校验通过后，将 `X-User-Id` 和 `X-User-Role` 注入到下游请求头中

**Service 层认证（`JwtAuthenticationFilter`）：**

- 基于 Spring Security 的认证过滤器
- 从 `X-User-Id` 请求头中提取用户 ID，构建 `Authentication` 对象供 Controller 使用

**JWT 配置：**

| 参数 | 值 | 说明 |
|------|-----|------|
| jwt.secret | 256 位密钥 | HS256 签名密钥 |
| jwt.expiration | 1800000 ms | accessToken 有效期 30 分钟 |
| jwt.refresh-expiration | 604800000 ms | refreshToken 有效期 7 天 |

---

## 4. 统一返回格式

所有接口统一使用 `ResultVO<T>` 作为返回类型：

```json
{
  "code": 200,
  "msg": "success",
  "data": { ... }
}
```

**使用方式：**

```java
// 成功返回数据
ResultVO.success(data);

// 成功无数据
ResultVO.success();

// 失败返回
ResultVO.fail(ResultCode.BAD_REQUEST);
ResultVO.fail(ResultCode.BAD_REQUEST, "自定义错误信息");
```

**错误码定义（`ResultCode.java`）：**

| Code | 常量名 | 说明 |
|------|--------|------|
| 200 | SUCCESS | 请求成功 |
| 400 | BAD_REQUEST | 请求参数错误 |
| 401 | UNAUTHORIZED | 未认证或 Token 过期 |
| 403 | FORBIDDEN | 无权限访问 |
| 404 | NOT_FOUND | 资源不存在 |
| 500 | SYSTEM_ERROR | 系统内部错误 |
| 50001 | FLASH_SOLD_OUT | 已售罄 |
| 50002 | FLASH_REPEAT | 重复购买（超过限购次数） |
| 50003 | FLASH_NOT_STARTED | 秒杀活动未开始 |
| 50004 | FLASH_ENDED | 秒杀活动已结束 |
| 50005 | STOCK_NOT_ENOUGH | 库存不足 |
| 50006 | CAPTCHA_ERROR | 验证码错误 |
| 50007 | RATE_LIMITED | 请求过于频繁 |

---

## 5. 枚举值说明

### FlashSaleStatusEnum（秒杀活动状态）

定义在 `com.flashsale.model.enums.FlashSaleStatusEnum`。

| 枚举值 | Code | 说明 | 触发方式 |
|--------|------|------|----------|
| PENDING | 0 | 待开始 | 创建活动时默认状态 |
| ACTIVE | 1 | 进行中 | 定时任务 `FlashSaleScheduler` 自动流转，或管理员手动变更；触发 Redis 缓存预热 |
| ENDED | 2 | 已结束 | 定时任务 `FlashSaleScheduler` 自动流转，或管理员手动变更 |
| CANCELLED | 3 | 已取消 | 管理员手动变更 |

**状态流转规则：**

```
PENDING(0) ──→ ACTIVE(1) ──→ ENDED(2)
     │              │
     └──────→ CANCELLED(3) ←──┘
```

### OrderStatusEnum（订单状态）

定义在 `com.flashsale.model.enums.OrderStatusEnum`。

| 枚举值 | Code | 说明 | 触发方式 |
|--------|------|------|----------|
| PENDING_PAYMENT | 0 | 待支付 | 消费者创建订单时默认状态 |
| PAID | 1 | 已支付 | 用户主动支付或管理员确认支付 |
| CANCELLED | 2 | 已取消 | 用户主动取消，或 `OrderScheduler` 超时自动取消（15 分钟未支付） |
| REFUNDED | 3 | 已退款 | 管理员操作退款 |

**状态流转规则：**

```
PENDING_PAYMENT(0) ──→ PAID(1) ──→ REFUNDED(3)
       │
       └──────→ CANCELLED(2)
```

### UserRoleEnum（用户角色）

| 枚举值 | 说明 |
|--------|------|
| USER | 普通用户 |
| ADMIN | 管理员 |

---

## 6. Redis Lua 脚本

### 库存预扣脚本（`stock_deduct.lua`）

位于 `flash-service/src/main/resources/scripts/stock_deduct.lua`，在 Redis 服务端单线程执行，保证原子性。

**参数说明：**

| 参数 | 含义 | 示例 |
|------|------|------|
| KEYS[1] | 库存 Key | `flash:stock:{flashSaleId}` |
| KEYS[2] | 用户已购计数 Key | `flash:user:purchased:{flashSaleId}:{userId}` |
| ARGV[1] | 每人限购数量 | `1` |
| ARGV[2] | 用户购买记录的 TTL（秒） | `3600` |

**返回值：**

| 返回值 | 含义 |
|--------|------|
| `1` | 购买成功：库存 -1，用户计数 +1 |
| `0` | 超过用户限购次数 |
| `-1` | 库存不足（已售罄） |

**执行逻辑：**

```lua
-- 1. 检查用户是否已超过限购次数
local purchased = tonumber(redis.call('GET', KEYS[2]) or '0')
if purchased >= tonumber(ARGV[1]) then
    return 0
end

-- 2. 检查库存是否充足
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock <= 0 then
    return -1
end

-- 3. 原子操作：扣库存 + 记录用户购买 + 设置过期
redis.call('DECR', KEYS[1])
redis.call('INCR', KEYS[2])
redis.call('EXPIRE', KEYS[2], ARGV[2])

return 1
```

### MQ 发送失败必须回滚 Redis

Lua 脚本执行成功后 Redis 状态已变更（库存 -1，用户计数 +1），如果后续 RocketMQ 同步发送失败，**必须回滚 Redis**，否则库存将永久丢失：

- **回滚操作**：`INCR stock` + `DECR userCount`
- **更好做法**：回滚也封装为 Lua 脚本，保证回滚本身原子。如果回滚中途 Redis 宕机，仍可能出现数据不一致
- **关键原则**：Redis 预扣是"乐观"操作，MQ 发送失败时不能假设 Redis 状态一定正确

### Redis Key 规范

| Key 模式 | 说明 | 来源 |
|----------|------|------|
| `flash:stock:{flashSaleId}` | 秒杀库存余量 | `RedisConstants.FLASH_STOCK_KEY` |
| `flash:sale:{flashSaleId}` | 秒杀活动详情缓存 | `RedisConstants.FLASH_SALE_KEY` |
| `flash:user:purchased:{flashSaleId}:{userId}` | 用户已购数量 | `RedisConstants.FLASH_USER_PURCHASED_KEY` |
| `flash:lock:{flashSaleId}` | Redisson 分布式锁 | `RedisConstants.FLASH_LOCK_KEY` |
| `flash:msg:processed:{messageKey}` | MQ 消息幂等记录 | `RocketMQConstants.MSG_PROCESSED_KEY` |
| `rate:limit:{key}:{userId\|ip:xxx}` | 接口限流滑动窗口（ZSET） | `RateLimitInterceptor` |
| `flash:captcha:{captchaId}` | 算术验证码答案 | `RedisConstants.CAPTCHA_KEY` |

---

## 7. RocketMQ 消息机制

### 基本配置

| 配置项 | 值 | 来源 |
|--------|-----|------|
| Topic | `flash-order-topic` | `RocketMQConstants.FLASH_ORDER_TOPIC` |
| Tag | `create` | `RocketMQConstants.TAG_CREATE` |
| Consumer Group | `flash-order-consumer-group` | `RocketMQConstants.ORDER_CONSUMER_GROUP` |
| Producer Group（API） | `flash-api-producer-group` | application.yml |
| Producer Group（Admin） | `flash-admin-producer-group` | application.yml |

### 消息体（`FlashOrderMessage`）

```java
public class FlashOrderMessage implements Serializable {
    private String messageKey;     // 消息唯一键：flashSaleId_userId_timestamp
    private Long flashSaleId;      // 秒杀活动 ID
    private Long userId;           // 用户 ID
    private Long itemId;           // 商品 ID
    private BigDecimal flashPrice; // 秒杀价格
}
```

### 消费者处理流程（`FlashOrderConsumer`）

```
收到消息
  │
  ├── 1. Redis 幂等校验：SETNX flash:msg:processed:{messageKey}
  │      └── 已处理 → 跳过
  │
  ├── 2. DB 幂等校验：FlashOrderMapper.selectByMessageKey()
  │      └── 已存在 → 跳过（Redis key 被驱逐时的兜底）
  │
  ├── 3. Redisson 分布式锁：flash:lock:{flashSaleId}（最长持有 10 秒）
  │
  ├── 4. 事务扣库存+创建订单：FlashOrderServiceImpl.deductStockAndCreateOrder()
  │      └── @Transactional → DB 幂等 → 乐观锁 deductStock → INSERT order
  │      └── 失败自动回滚
  │
  └── 异常处理：
         ├── BusinessException（售罄）→ 吞没不重试
         └── 其他 Exception → re-throw 触发 RocketMQ 重试
```

### 消费者启用控制

消费者仅在 flash-api 模块中启用，通过配置项控制：

```yaml
# flash-api application.yml
flash:
  flash:
    consumer:
      enabled: true    # 启用消费者

# flash-admin application.yml
flash:
  flash:
    consumer:
      enabled: false   # 禁用消费者，避免同组竞争消费
```

实现方式：`@ConditionalOnProperty(name = "flash.flash.consumer.enabled", havingValue = "true")`

### 常见踩坑

**坑1：`consumeFromWhere` 默认值**

`rocketmq-spring-boot-starter 2.3.0` 默认 `CONSUME_FROM_LAST_OFFSET`。消费者启动后只接收**启动后**到达的新消息，历史消息静默跳过且不报错。开发调试阶段容易漏消息。

如需消费历史消息，通过 `BeanPostProcessor` 修改：

```java
@Component
public class RocketMQListenerCustomizer implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DefaultMQPushConsumer consumer) {
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        }
        return bean;
    }
}
```

> 生产环境推荐保持 `CONSUME_FROM_LAST_OFFSET`，配合 offset 持久化。`CONSUME_FROM_FIRST_OFFSET` 仅适合开发调试。

**坑2：Broker 就绪时序**

Consumer 在 Broker 完全就绪前启动，可能**不报错但实际未订阅成功**。Broker 日志出现 `boot success` 后再启动应用，否则消息可能静默丢失。

---

## 8. 前端开发

### 8.1 技术栈

| 项 | 技术 |
|----|------|
| 框架 | Vue 3 |
| 构建工具 | Vite |
| HTTP 客户端 | Axios |
| 路由 | Vue Router |

### 8.2 代理配置

**用户前端（flash-frontend，端口 5173）：**

```javascript
// vite.config.js
proxy: {
  '/api':  { target: 'http://localhost:8080', changeOrigin: true },
  '/admin': { target: 'http://localhost:8080', changeOrigin: true }
}
```

**管理前端（flash-admin-frontend，端口 5174）：**

```javascript
// vite.config.js
proxy: {
  '/admin': { target: 'http://localhost:8080', changeOrigin: true }
}
```

所有前端请求先到达 Vite 开发服务器，再由代理转发到 Gateway（8080），Gateway 根据路径转发到对应的后端服务。

### 8.3 认证流程

```
1. 用户登录 → 获取 accessToken + refreshToken
2. 将 accessToken 存入 localStorage（key: 'accessToken'）
3. Axios 请求拦截器自动添加 Authorization: Bearer {token} 请求头
4. 收到 401 响应时，清除 Token 并跳转到登录页
```

**用户前端 Token Key：** `accessToken`、`refreshToken`
**管理前端 Token Key：** `adminToken`

### 8.4 页面路由

**用户前端（flash-frontend）：**

| Path | 组件 | 说明 | 需要认证 |
|------|------|------|----------|
| `/` | Home.vue | 首页（商品列表、秒杀活动列表） | 是 |
| `/login` | Login.vue | 登录页 | 否 |
| `/register` | Register.vue | 注册页 | 否 |
| `/flash-sale/:id` | FlashSaleDetail.vue | 秒杀活动详情与下单 | 是 |
| `/orders` | OrderList.vue | 我的订单列表 | 是 |

**管理前端（flash-admin-frontend）：**

| Path | 组件 | 说明 | 需要认证 |
|------|------|------|----------|
| `/login` | Login.vue | 管理员登录页 | 否 |
| `/` | Dashboard.vue | 仪表盘（布局容器，默认重定向到 /items） | 是 |
| `/items` | ItemList.vue | 商品管理 | 是 |
| `/flash-sales` | FlashSaleList.vue | 秒杀活动管理 | 是 |
| `/orders` | OrderList.vue | 订单管理 | 是 |
| `/users` | UserList.vue | 用户管理 | 是 |

管理前端使用嵌套路由，`Dashboard.vue` 作为父级布局容器，包含侧边栏导航和 `<router-view>` 插槽。

---

## 9. 代码规范

### 9.1 命名规范

| 类别 | 规范 | 示例 |
|------|------|------|
| 类名 | 大驼峰（PascalCase） | `FlashSaleService`、`OrderStatusEnum` |
| 方法名 | 小驼峰（camelCase） | `getActiveFlashSales()`、`cancelOrder()` |
| 变量名 | 小驼峰（camelCase） | `flashSaleId`、`orderStatus` |
| 常量 | 大写下划线 | `FLASH_STOCK_KEY`、`ORDER_TIMEOUT_MINUTES` |
| 数据库字段 | 小写下划线（snake_case） | `user_id`、`flash_price`、`create_time` |
| 包名 | 全小写 | `com.flashsale.service.impl` |

### 9.2 统一返回

- 所有 Controller 方法统一返回 `ResultVO<T>`，禁止直接返回裸数据
- 使用 `ResultVO.success(data)` 返回成功响应
- 使用 `ResultVO.fail(ResultCode.XXX)` 返回失败响应
- 错误码统一在 `ResultCode` 枚举中维护，不要硬编码数字

### 9.3 异常处理

- 业务异常统一抛出 `BusinessException`，由 `GlobalExceptionHandler` 捕获并转换为 `ResultVO`
- 认证异常使用 `UnauthorizedException`
- 权限异常使用 `ForbiddenException`
- Controller 层不做 try-catch，异常由全局处理器统一处理

### 9.4 数据库规范

- 所有表必须包含以下基础字段：`id`（主键）、`create_time`、`update_time`、`is_deleted`
- 逻辑删除字段 `is_deleted`：0 表示正常，1 表示已删除
- 由 MyBatis-Plus 的 `MyMetaObjectHandler` 自动填充 `create_time` 和 `update_time`
- 禁止使用 `SELECT *`，查询时必须指定具体字段
- 使用 MyBatis-Plus 提供的逻辑删除功能，删除操作实际执行 UPDATE

### 9.5 建表规范

参考 `sql/init.sql`，所有表遵循以下规范：

```sql
-- 基础字段
`id` BIGINT AUTO_INCREMENT PRIMARY KEY,
`create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
`update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
`is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=已删除'

-- 字符集
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
```

### 9.6 定时任务说明

| 任务 | 位置 | 频率 | 说明 |
|------|------|------|------|
| `FlashSaleScheduler` | flash-admin | 每 60 秒 | 自动将到达开始时间的 PENDING 活动激活（含 Redis 缓存预热），将超过结束时间的 ACTIVE 活动结束 |
| `OrderScheduler` | flash-admin | 每 300 秒 | 自动取消超过 15 分钟未支付的订单，并归还 DB 库存和 Redis 库存 |

---

> **快速启动**：环境搭建、中间件部署、服务启动请参考 [02-部署指南](./02-deployment.md)。默认管理员账号 `admin / admin123`，由 `DataInitRunner` 在首次启动时自动创建。
