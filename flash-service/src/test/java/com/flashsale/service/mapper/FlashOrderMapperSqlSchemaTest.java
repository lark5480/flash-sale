package com.flashsale.service.mapper;

import com.flashsale.mapper.FlashOrderMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单表裸 SQL 与建表脚本的一致性守护。
 * <p>
 * {@code FlashOrderMapper} 里手写 SQL 的列名没有任何编译期或测试期校验：写成 `massage_key` 这类
 * 拼错、或删掉 {@code SELECT *} 后漏列，Maven 与现有用例都不会报错，只会在运行时以字段为 null
 * 或 SQL 异常的形式暴露。这里把 {@code sql/init.sql} 的 flash_order 列清单当作唯一真源来比对。
 */
class FlashOrderMapperSqlSchemaTest {

    /** 建表语句块：CREATE TABLE ... `flash_order` ( ... ) ENGINE */
    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS `flash_order` \\((.*?)\\n\\) ENGINE", Pattern.DOTALL);

    /** DDL 列定义行：`col` TYPE ...（KEY / PRIMARY KEY 行不以反引号开头，天然被排除） */
    private static final Pattern DDL_COLUMN = Pattern.compile("^\\s+`([a-z_]+)`\\s+[A-Z]", Pattern.MULTILINE);

    /** 全表扫描的裸 SQL —— 项目规则明确禁止，靠人工 review 拦不住 */
    private static final Pattern SELECT_STAR = Pattern.compile("SELECT\\s+\\*", Pattern.CASE_INSENSITIVE);

    /** 分页查询 LEFT JOIN item 带出的两列，不属于 flash_order，比对前显式排除 */
    private static final Set<String> ITEM_SIDE_COLUMNS = Set.of("name", "image");

    @Test
    @DisplayName("订单 Mapper 不得出现 SELECT *，且 selectByMessageKey 必须覆盖 flash_order 全部列")
    void selectColumnsMatchSchema() throws IOException {
        Set<String> tableColumns = flashOrderColumns();
        String sql = selectSql(selectByMessageKey());

        assertThat(SELECT_STAR.matcher(sql).find())
                .as("selectByMessageKey 又写回 SELECT *（项目规范第 10 条禁止）")
                .isFalse();
        assertThat(columnList(sql, "SELECT", "FROM"))
                .as("selectByMessageKey 的列清单与 sql/init.sql 的 flash_order 不一致：漏列会静默丢字段，多列会 SQL 报错")
                .containsExactlyInAnyOrderElementsOf(tableColumns);
    }

    @Test
    @DisplayName("insertWithId 与用户订单分页查询引用的裸列必须都存在于表结构中")
    void handWrittenColumnsExistInSchema() throws IOException {
        Set<String> tableColumns = flashOrderColumns();

        assertThat(columnList(insertSql(insertWithId()), "INSERT INTO flash_order (", ")"))
                .isSubsetOf(tableColumns);
        // 分页查询带出商品表的 name/image，这两列在 item 表，单独排除后再比
        Set<String> pageColumns = columnList(selectSql(selectUserOrderPage()), "SELECT", "FROM");
        assertThat(pageColumns).containsAll(ITEM_SIDE_COLUMNS);
        assertThat(pageColumns.stream().filter(column -> !ITEM_SIDE_COLUMNS.contains(column)).toList())
                .isSubsetOf(tableColumns);
    }

    private Set<String> flashOrderColumns() throws IOException {
        String ddl = Files.readString(findInitSql());
        Matcher block = TABLE_BLOCK.matcher(ddl);
        if (!block.find()) {
            throw new AssertionError("没能在 sql/init.sql 里定位到 flash_order 建表语句，正则需随脚本格式同步");
        }
        Set<String> columns = new LinkedHashSet<>();
        Matcher column = DDL_COLUMN.matcher(block.group(1));
        while (column.find()) {
            columns.add(column.group(1));
        }
        return columns;
    }

    /**
     * 取 SQL 里 startKeyword 与 endKeyword 之间的逗号分隔列名，去掉 {@code <script>} 标签、
     * {@code o.} 前缀与 {@code AS xxx} 别名。
     */
    private Set<String> columnList(String sql, String startKeyword, String endKeyword) {
        String cleanSql = sql.replaceAll("</?script>", "");
        int start = cleanSql.indexOf(startKeyword);
        if (start < 0) {
            throw new AssertionError("SQL 里找不到片段起点：" + startKeyword);
        }
        int from = cleanSql.indexOf(endKeyword, start + startKeyword.length());
        if (from < 0) {
            throw new AssertionError("SQL 里找不到片段终点：" + endKeyword);
        }
        String body = cleanSql.substring(start + startKeyword.length(), from);
        Set<String> columns = new LinkedHashSet<>();
        for (String part : body.split(",")) {
            String column = part.trim().toLowerCase(Locale.ROOT);
            column = column.replaceAll("\\s+as\\s+\\w+$", "");
            column = column.replaceFirst("^[a-z]+\\.", "");
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    private String selectSql(Method method) {
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private String insertSql(Method method) {
        return String.join("\n", method.getAnnotation(Insert.class).value());
    }

    private Method selectByMessageKey() {
        return named("selectByMessageKey");
    }

    private Method insertWithId() {
        return named("insertWithId");
    }

    private Method selectUserOrderPage() {
        return named("selectUserOrderPage");
    }

    private Method named(String name) {
        for (Method method : FlashOrderMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("FlashOrderMapper 里没有方法：" + name + "（改名要同步本用例）");
    }

    private Path findInitSql() {
        Path fromModule = Path.of("../sql/init.sql");
        Path fromRoot = Path.of("sql/init.sql");
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        throw new AssertionError("找不到 sql/init.sql，测试需在 Maven 模块工作目录下运行");
    }
}
