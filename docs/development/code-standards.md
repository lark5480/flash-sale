# 代码规范

> 本页是**面向开发者的完整编码规范正文**，可独立阅读。同一套规则面向 AI 编码工具的精简版收在仓库根 [`AGENTS.md`](https://github.com/lark5480/flash-sale/blob/master/AGENTS.md) 的「代码规范」「数据库规范」两节——两者仅有短句级重叠，需要解释和示例的部分只写在这里。

## 1. 通用规范

1. 遵循**阿里巴巴 Java 开发手册**。
2. 命名约定见[下方表格](#_2-命名规范)。
3. **统一返回 `ResultVO<T>`**：成功用 `ResultVO.success(data)`，失败用 `ResultVO.fail(ResultCode.xxx)` 或 `ResultVO.fail(code, msg)`；错误码统一在 `ResultCode` 枚举中维护，禁止在调用处硬编码数字。完整字段与错误码清单见[统一返回与枚举](./response-and-enums.md)。
4. **全局异常处理走 `GlobalExceptionHandler`（`@RestControllerAdvice`）**；自定义异常只用 `BusinessException`、`UnauthorizedException`、`ForbiddenException`。Controller 层不做 try-catch，业务异常一律抛出交由全局处理器统一转换。
5. 工具类放在 `flash-common` 的 `util` 包，类名使用**单数**（`JwtUtil`、`PasswordUtil`）。
6. 实体继承 `BaseEntity`（提供 `id`、`createTime`、`updateTime`、`isDeleted` + `@TableLogic`）；`create_time` / `update_time` 由 `MyMetaObjectHandler` 自动填充，业务代码不手动 set。
7. **依赖注入使用构造器注入**（`private final` 字段 + 构造器），不使用 `@Autowired` 字段注入。
8. **日志使用 SLF4J**：`private static final Logger log = LoggerFactory.getLogger(Xxx.class);`。
9. 禁止 `*` 号导入。
10. 禁止 `select *`，使用 `LambdaQueryWrapper` 构建查询、指定具体字段。
11. 逻辑删除字段为 `is_deleted`（`Integer` 类型，`0`=未删除，`1`=已删除）；删除操作走 MyBatis-Plus 逻辑删除，实际执行 UPDATE。
12. 实体类使用**手动 getter/setter，不使用 Lombok `@Data`**。
13. `util` 与 `enum` 类必须有类级 Javadoc，枚举常量必须有注释说明业务含义。
14. `if/else/for/while` 必须使用大括号，即使只有一行。
15. 多 DB 写操作方法必须使用 `@Transactional(rollbackFor = Exception.class)`。

## 2. 命名规范

| 类别 | 规范 | 示例 |
|------|------|------|
| 类名 | 大驼峰（PascalCase） | `FlashSaleService`、`OrderStatusEnum` |
| 方法名 | 小驼峰（camelCase） | `getActiveFlashSales()`、`cancelOrder()` |
| 变量名 | 小驼峰（camelCase） | `flashSaleId`、`orderStatus` |
| 常量 | 大写下划线 | `FLASH_STOCK_KEY`、`ORDER_TIMEOUT_MINUTES` |
| 数据库字段 | 小写下划线（snake_case） | `user_id`、`flash_price`、`create_time` |
| 包名 | 全小写 | `com.flashsale.service.impl` |

## 3. 代码放置约定

新增代码放回对应模块（依赖链见[模块依赖与包结构](./structure.md)）：

| 类型 | 模块 |
|------|------|
| Entity / DTO / VO / 枚举 | `flash-model` |
| MyBatis-Plus Mapper | `flash-mapper` |
| Service 接口与实现、MQ 生产/消费、缓存与过滤器 | `flash-service` |
| C 端 Controller | `flash-api` |
| 管理端 Controller、定时任务 | `flash-admin` |
| 工具类、常量、统一返回、异常处理 | `flash-common` |

## 4. 数据库规范

字段名使用**小写 + 下划线**，不得使用 SQL 关键字。每张表必须包含以下基础字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `BIGINT` | 自增主键 |
| `create_time` | `DATETIME` | 创建时间，自动填充 |
| `update_time` | `DATETIME` | 更新时间，自动填充 |
| `is_deleted` | `INT`（`TINYINT`） | 逻辑删除，`0`/`1` |

### 建表模板

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

## 5. 相关页面

- 完整表结构与索引：[数据设计](../architecture/data-design.md)
- 定时任务与消费者隔离：[定时任务与消费者隔离](../architecture/scheduling-and-isolation.md)
- 测试要求：[测试](./testing.md)
- 贡献流程与提交前检查：[CONTRIBUTING](https://github.com/lark5480/flash-sale/blob/master/CONTRIBUTING.md)
