# RocketMQ 消息机制

## 1. 基本配置

| 配置项 | 值 | 来源 |
|--------|-----|------|
| Topic | `flash-order-topic` | `RocketMQConstants.FLASH_ORDER_TOPIC` |
| Tag | `create` | `RocketMQConstants.TAG_CREATE` |
| Consumer Group | `flash-order-consumer-group` | `RocketMQConstants.ORDER_CONSUMER_GROUP` |
| Producer Group（API） | `flash-api-producer-group` | application.yml |
| Producer Group（Admin） | `flash-admin-producer-group` | application.yml |

## 2. 消息体（`FlashOrderMessage`）

```java
public class FlashOrderMessage implements Serializable {
    private String messageKey;     // 消息唯一键：flashSaleId_userId_timestamp
    private Long flashSaleId;      // 秒杀活动 ID
    private Long userId;           // 用户 ID
    private Long itemId;           // 商品 ID
    private BigDecimal flashPrice; // 秒杀价格
}
```

## 3. 消费者处理流程（`FlashOrderConsumer`）

```
收到消息
  │
  ├── 1. 终态判定：GET flash:msg:result:{messageKey}
  │      └── 状态按前缀解析（FAILED:原因 也算终态）→ 跳过（标记只在业务终态后写入，不会短路重试）
  │
  ├── 2. DB 幂等校验：FlashOrderMapper.selectByMessageKey()
  │      └── 已存在 → markSettledOnly(DONE)：只补标记，不递减在途
  │                  （订单由更早一次投递落库，那笔预扣已在那次收敛）
  │
  ├── 3. Redisson 分布式锁：flash:lock:{flashSaleId}（最长持有 10 秒）
  │
  ├── 4. 事务扣库存+创建订单：FlashOrderServiceImpl.deductStockAndCreateOrder()
  │      └── @Transactional：
  │           ├─ messageKey 幂等复查 → 命中返回 null（调用方不递减在途）
  │           ├─ checkPurchaseLimit：countQuotaOccupied 限购 DB 兜底
  │           │    （Redis 限购键会过期/丢失，缺一层就无声变成「不限购」；
  │           │     同一场次已被步骤 3 串行化，普通 COUNT 即可，无需 FOR UPDATE）
  │           │    （限购口径 = 整场累计、无每日清零：`status NOT IN (2,3)` 即待支付/已支付
  │           │     占额度，取消与退款释放，与归还脚本递减 Redis 限购计数同一口径）
  │           ├─ 乐观锁 deductStock（stock > 0 才更新，返回 0 抛 FLASH_SOLD_OUT）
  │           └─ INSERT order（失败则 deductStock 一并回滚）
  │
  └── 异常处理（终态标记与在途计数统一交给 FlashOrderSettler）：
         ├── 本次真正落库 → settleAndRelease(DONE)：SETNX 成功才 DECR 在途
         ├── BusinessException（售罄/超限购/活动结束）→ settleAndRelease(failedMarker(e.getMessage()))
         │      写入 `FAILED:已达每人限购数量` 这类带原因的标记，吞没不重试；客户端轮询即可拿到真因
         ├── 其他 Exception → 不写标记，re-throw 触发 RocketMQ 重试
         └── 重试耗尽进死信 → FlashOrderDeadLetterConsumer.settleAndRelease(failedMarker("处理多次重试仍失败，请重新下单"))
```

::: tip 在途收敛不变式
一笔预扣的在途计数恰好递减一次，且当且仅当某次投递真正写入了终态标记。`FlashOrderSettler` 用 `setIfAbsent` 的返回值同时充当「这次投递是否负责收敛」的判据，因此主消费者与死信消费者重复处理同一条消息不会多减。误差方向是单向可接受的：多减会放出虚假库存（超卖方向），少减只会少卖。
:::

## 4. 消费者启用控制

消费者仅在 flash-api 模块中启用，通过配置项控制（详见[定时任务与消费者隔离](../architecture/scheduling-and-isolation.md)）：

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

## 5. 消息可靠性

- RocketMQ Broker `flushDiskType = SYNC_FLUSH`（同步刷盘，消息不丢）
- Consumer `maxReconsumeTimes = 3`（重试 3 次后进死信队列）
- 死信队列消费者 `FlashOrderDeadLetterConsumer` 记录重试耗尽消息供人工补偿，并补写 `FAILED:原因` 终态标记，避免客户端永远轮询到 PROCESSING
- Broker 原生 Prometheus 指标导出（端口 5557）

## 6. 常见踩坑

**坑1：`consumeFromWhere` 默认值**

`rocketmq-spring-boot-starter 2.3.0` 默认 `CONSUME_FROM_LAST_OFFSET`。消费者启动后只接收**启动后**到达的新消息，历史消息静默跳过且不报错。开发调试阶段容易漏消息。如需消费历史消息，通过 `BeanPostProcessor` 修改：

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
