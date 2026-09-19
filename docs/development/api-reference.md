# API 接口文档

> 本页是接口清单的**唯一详细出处**（README 的「API 概览」为精简版并链向此处）。认证机制见[安全与认证](../architecture/security.md)。

## 1. 用户端 API（/api）

通过 Gateway（端口 8080）按路径 `/api/**` 转发到 flash-api（端口 8081）。

| Method | Path | 说明 | 需要认证 |
|--------|------|------|----------|
| POST | `/api/auth/register` | 用户注册 | 否 |
| POST | `/api/auth/login` | 用户登录（需验证码），返回 accessToken 和 refreshToken | 否 |
| POST | `/api/auth/refresh?refreshToken=` | 刷新 Token | 否 |
| GET | `/api/auth/captcha` | 获取算术验证码（返回 captchaId + 表达式） | 否 |
| GET | `/api/item/list?page=1&size=10` | 商品列表（分页） | 是 |
| GET | `/api/item/{id}` | 商品详情 | 是 |
| GET | `/api/flash-sale/active` | 当前进行中的秒杀活动列表 | 否（游客可浏览） |
| GET | `/api/flash-sale/{id}` | 秒杀活动详情（含关联商品信息） | 否（仅 GET，游客可浏览） |
| POST | `/api/flash-sale/{id}/purchase` | 秒杀下单（需验证码），返回 messageKey | 是 |
| GET | `/api/order/status?messageKey=` | 轮询订单异步处理状态（PROCESSING / DONE / FAILED；业务终态失败时另返回 `failReason`，如「已达每人限购数量」） | 是 |
| GET | `/api/order/list?page=1&size=10&status=&keyword=` | 我的订单列表（分页，支持状态筛选与订单号/商品名称搜索） | 是 |
| GET | `/api/order/{id}` | 订单详情 | 是 |
| POST | `/api/order/{id}/pay` | 支付订单 | 是 |
| POST | `/api/order/{id}/cancel` | 取消订单 | 是 |
| POST | `/api/order/{id}/refund` | 退款 | 是 |
| DELETE | `/api/order/{id}` | 删除已取消订单 | 是 |

**秒杀下单流程：**

1. 客户端调用 `POST /api/flash-sale/{id}/purchase`
2. 服务端确保 Redis 库存状态键就绪（缺失时按 `DB stock − 在途` 用 SETNX 补建，已存在完全不覆盖），再执行 Redis Lua 脚本进行库存预扣和限购校验
3. 预扣成功后，通过 RocketMQ 发送异步下单消息
4. 立即返回 `messageKey` 给客户端
5. 客户端使用 `messageKey` 轮询 `GET /api/order/status` 获取订单创建结果
6. 消费者异步处理：幂等校验 -> 分布式锁 -> 限购 DB 兜底 -> DB 乐观锁扣库存 -> 创建订单 -> 写终态标记并收敛在途计数

完整时序见[秒杀下单核心链路](../architecture/flash-sale-flow.md)。

## 2. 管理端 API（/admin）

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

::: warning 进行中活动禁止改库存
`PUT /admin/flash-sale` 对**进行中（ACTIVE）**的活动拒绝修改 `stock`，返回 `BAD_REQUEST`。该列既是总量也是台账，而后台表单提交的是打开页面那一刻的快照值，全量 `updateById` 会把它写回旧值或更大值，等于凭空放出已卖出的库存。需要补库存时走「结束活动 → 改库存 → 重新激活」——重新激活时服务端会先删除上一周期遗留的旧库存键，再按新 `DB stock − 在途` 重建。admin 前端在活动进行中会把「库存」输入置灰并从提交体里剥离该字段。
:::

### 库存归属与展示口径（易踩坑）

1. **库存只挂在秒杀活动上**：`item` 表**没有** `stock` 字段，商品只是 SKU（名称/图片/原价）。所谓"库存"一律指 `flash_sale.stock`，不存在"商品库存"维度，前端展示的 `sale.stock` 即此列。
2. **同一个场次的两个数字天然不等，属预期**：
   - 后台秒杀列表 `GET /admin/flash-sale/list` 读 **DB 实时台账**（MQ 落库后才减）；
   - C 端详情 / 首页列表对 **ACTIVE** 场次读 `flash:stock:{id}`（已扣掉在途预扣）。
   在「Redis 已预扣、MQ 尚未落库」的窗口内两者相差在途量，不是 bug。非 ACTIVE 场次一律以 DB 为准。
3. **补货路径**：结束活动 → 改 DB stock → 重新激活（激活时会先删旧库存键再按新值重建）。

## 3. 统一返回与错误码

所有接口统一返回 `ResultVO<T>`，错误码定义见[统一返回与枚举](./response-and-enums.md)。
