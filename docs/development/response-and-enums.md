# 统一返回与枚举

## 1. 统一返回格式

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

## 2. 枚举值说明

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
