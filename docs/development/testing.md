# 测试

**现状**：后端测试集中在秒杀链路的不变式上，分布在 `flash-common` 与 `flash-service` 两个模块（**用例数不写进文档**，以 `mvn clean verify` 的 surefire 汇总为准，避免加一个用例就要回来改数字）；CI 跑 `mvn -B clean verify`（已不再 `-DskipTests`）。

## 1. 测试类与覆盖的不变式

| 测试类 | 覆盖的不变式 |
|--------|--------------|
| `RedisConstantsTest` | 状态键 TTL 必须覆盖整场 + 宽限期；`randomTtl` 的已知缺陷被显式钉住 |
| `FlashStockStateTest` | 库存键存在就绝不覆盖、重建 = `DB stock − 在途`、在途读失败 fail-closed、归还三模式参数 |
| `FlashOrderSettlerTest` | 只有写入终态标记的投递递减在途；重复投递与写失败都不重复减 |
| `StockScriptRedisIntegrationTest` | 两处 Lua 对**真实 Redis** 的语义：原子扣减、TTL 只在首次设置、键缺失不回源、归还的 EXISTS 守卫、在途不归负、200 并发抢 50 库存不超卖 |
| `RocketMQConstantsTest` | 终态标记 `FAILED[:原因]` 的编解码；带原因的 FAILED 仍须被判成 FAILED |
| `FlashSaleDetailStockTest` / `FlashStockMetricsSamplerTest` / `FlexibleLocalDateTimeDeserializerTest` | 展示口径读 Redis 权威余量、指标采样、时间格式兼容 |

## 2. 怎么跑

```bash
mvn clean verify                                        # 全量（含真实 Redis 集成测试，需 Docker）
mvn -o -pl flash-service -am clean test                  # 只测一个模块：-am 不可省
mvn -o -pl flash-service -am test -Dtest=StockScriptRedisIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false              # 只跑一个类
```

- `-am` 必须带：否则兄弟模块会按本地仓库里**已安装的旧 jar** 解析，报出 `NoSuchMethodError` 这类容易误判成代码缺陷的错。
- `StockScriptRedisIntegrationTest` 标注了 `@Testcontainers(disabledWithoutDocker = true)`，没有 Docker 的机器整类跳过而不是失败。

## 3. 约定

改动秒杀链路（库存 / 限购 / 在途 / 终态收敛）的不变式**必须带可跑测试**，并且用「注入回归 → 确认只被指定用例杀掉」验证测试不是空跑；跑 `java -jar` 前一律 `mvn clean package`，别信增量构建。

**目前没有自动化守护的部分**：api 层鉴权清单（与网关白名单的一致性只能靠端到端实测）、MQ 真实投递与重试/死信、DB 落库 SQL、网关与启动流程、wrk 量级并发。

> 贡献流程中的测试要求见 [CONTRIBUTING](https://github.com/lark5480/flash-sale/blob/master/CONTRIBUTING.md#测试要求)。
