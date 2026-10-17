package org.dromara.system.sync;

import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.bo.SysDeptBo;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验组织来源字段的实体契约与初始化 SQL 契约。
 *
 * <p>本测试不启动 Spring 上下文，只做反射与 SQL 文本断言：字段一旦被改名、改类型或从
 * 实体、BO、VO 中移除，或初始化 SQL 与实体列不一致，都会在这里立刻失败。</p>
 */
@DisplayName("组织来源字段契约测试")
class OrgSyncContractTest {

    /** sys_dept 与 SysDeptVo 必须声明的 10 个组织来源字段及其 Java 类型。 */
    private static final Map<String, Class<?>> DEPT_SOURCE_FIELDS = orderedFields(
        "deptType", String.class,
        "businessId", String.class,
        "hrmSubcompanyId", String.class,
        "hrmSupdepartmentId", String.class,
        "hrmSupsubcompanyId", String.class,
        "hrmCode", String.class,
        "hrmFullName", String.class,
        "hrmCanceled", String.class,
        "hrmLastChangeTime", LocalDateTime.class,
        "lastSyncTime", LocalDateTime.class
    );

    /** sys_user 与 SysUserVo 必须声明的 7 个组织来源字段及其 Java 类型。 */
    private static final Map<String, Class<?>> USER_SOURCE_FIELDS = orderedFields(
        "hrmUserId", String.class,
        "hrmWorkcode", String.class,
        "hrmStatus", String.class,
        "hrmDepartmentId", String.class,
        "hrmSubcompanyId", String.class,
        "hrmLastChangeDate", LocalDate.class,
        "hrmSyncTime", LocalDateTime.class
    );

    /**
     * 部门实体与部门视图对象都要暴露完整的组织来源字段，供同步逻辑读写。
     */
    @Test
    void deptEntityHasSourceFields() {
        assertDeclaresFields(SysDept.class, DEPT_SOURCE_FIELDS);
        assertDeclaresFields(SysDeptVo.class, DEPT_SOURCE_FIELDS);
    }

    /**
     * 部门业务对象只能携带人工维护的绑定字段，不能暴露同步回写的来源字段。
     */
    @Test
    void deptBoOnlyCarriesBinding() {
        Map<String, Class<?>> boFields = declaredFields(SysDeptBo.class);
        assertThat(boFields).containsEntry("deptType", String.class);
        assertThat(boFields).containsEntry("businessId", String.class);
        assertThat(boFields.keySet()).noneMatch(OrgSyncContractTest::startsWithHrm);
    }

    /**
     * 用户业务对象不能携带来源字段；用户实体与视图对象要暴露 7 个来源字段。
     */
    @Test
    void userBoCannotCarrySourceFields() {
        assertThat(declaredFields(SysUserBo.class).keySet()).noneMatch(OrgSyncContractTest::startsWithHrm);
        assertDeclaresFields(SysUser.class, USER_SOURCE_FIELDS);
        assertDeclaresFields(SysUserVo.class, USER_SOURCE_FIELDS);
    }

    /**
     * 初始化 SQL 必须建出对应的列、索引与同步按钮权限。
     */
    @Test
    void initSqlDeclaresSourceColumns() {
        String sql = initSql();
        assertThat(sql).contains("uk_sys_dept_hrm_identity (dept_type, business_id)");
        assertThat(sql).contains("idx_sys_dept_hrm_subcompany");
        assertThat(sql).contains("idx_sys_user_hrm_user_id");
        assertThat(sql).contains("'system:dept:sync'");
        assertThat(sql).contains("'system:user:sync'");
        List<String> deptColumns = columnNames(tableBody(sql, "sys_dept"));
        DEPT_SOURCE_FIELDS.keySet()
            .forEach(field -> assertThat(deptColumns).describedAs("sys_dept 缺少 %s 对应的列", field)
                .contains(toSnakeCase(field)));
        List<String> userColumns = columnNames(tableBody(sql, "sys_user"));
        USER_SOURCE_FIELDS.keySet()
            .forEach(field -> assertThat(userColumns).describedAs("sys_user 缺少 %s 对应的列", field)
                .contains(toSnakeCase(field)));
    }

    /**
     * 同步按钮只对超级管理员开放，初始化角色不得预先获得这两个权限。
     */
    @Test
    void initSqlDoesNotGrantSyncButtonsToRoles() {
        Matcher matcher = Pattern.compile(
            "insert\\s+into\\s+sys_role_menu\\s+values\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)",
            Pattern.CASE_INSENSITIVE).matcher(initSql());
        List<String> granted = new ArrayList<>();
        while (matcher.find()) {
            granted.add(matcher.group(2));
        }
        assertThat(granted).isNotEmpty();
        assertThat(granted).doesNotContain("1761400000000001700", "1761400000000001701");
    }

    /**
     * 初始化数据使用不带列名的 values 插入，补齐列后每条种子数据的取值个数必须与列数一致。
     *
     * <p>个数不一致只会在真正建库时失败，普通单元测试与编译都发现不了，所以在此守住。</p>
     */
    @Test
    void initSqlPositionalInsertArityMatchesColumns() {
        String sql = initSql();
        assertInsertArity(sql, "sys_dept");
        assertInsertArity(sql, "sys_user");
    }

    /**
     * 读取 MySQL 初始化脚本；Maven 以模块目录为工作目录运行测试，因此向上定位仓库根目录。
     */
    private static String initSql() {
        Path moduleDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path current = moduleDir; current != null; current = current.getParent()) {
            Path candidate = current.resolve("script").resolve("sql").resolve("ry_vue.sql");
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("读取初始化脚本失败：" + candidate, e);
                }
            }
        }
        throw new IllegalStateException("未能从工作目录定位 RuoYi-Vue-Plus/script/sql/ry_vue.sql：" + moduleDir);
    }

    /**
     * 断言目标类声明的字段名与类型完全匹配预期集合。
     */
    private static void assertDeclaresFields(Class<?> type, Map<String, Class<?>> expected) {
        Map<String, Class<?>> actual = declaredFields(type);
        expected.forEach((name, fieldType) ->
            assertThat(actual)
                .describedAs("%s.%s 的声明", type.getSimpleName(), name)
                .containsEntry(name, fieldType));
    }

    /**
     * 收集类自身声明的实例字段，键为字段名，值为字段类型。
     */
    private static Map<String, Class<?>> declaredFields(Class<?> type) {
        Map<String, Class<?>> fields = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            fields.put(field.getName(), field.getType());
        }
        return fields;
    }

    /**
     * 判断字段名是否来自组织来源（历史命名统一以 hrm 开头）。
     */
    private static boolean startsWithHrm(String name) {
        return name.startsWith("hrm");
    }

    /**
     * 按名称、类型交替的入参构造有序字段表。
     */
    private static Map<String, Class<?>> orderedFields(Object... nameAndType) {
        Map<String, Class<?>> fields = new LinkedHashMap<>();
        for (int i = 0; i < nameAndType.length; i += 2) {
            fields.put((String) nameAndType[i], (Class<?>) nameAndType[i + 1]);
        }
        return fields;
    }

    /**
     * 取出 create table 语句括号内的列定义部分。
     */
    private static String tableBody(String sql, String table) {
        Matcher matcher = Pattern.compile(
            "create\\s+table\\s+" + Pattern.quote(table) + "\\s*\\((.*?)\\)\\s*engine", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(sql);
        assertThat(matcher.find()).describedAs("初始化 SQL 中缺少 create table %s", table).isTrue();
        return matcher.group(1);
    }

    /**
     * 把列定义文本按行解析为 列名 → 定义，跳过索引与约束行。
     */
    private static List<String> columnNames(String body) {
        List<String> columns = new ArrayList<>();
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase();
            if (lower.startsWith("primary key") || lower.startsWith("key ")
                || lower.startsWith("unique key") || lower.startsWith("index ")
                || lower.startsWith("unique index") || lower.startsWith("constraint")) {
                continue;
            }
            columns.add(line.split("\\s+", 2)[0]);
        }
        return columns;
    }

    /**
     * 验证每条初始化数据的取值个数与建表列数一致。
     */
    private static void assertInsertArity(String sql, String table) {
        int columnCount = columnNames(tableBody(sql, table)).size();
        Matcher matcher = Pattern.compile(
            "insert\\s+into\\s+" + Pattern.quote(table) + "\\s+values\\s*\\(",
            Pattern.CASE_INSENSITIVE).matcher(sql);
        int rows = 0;
        while (matcher.find()) {
            String values = parenthesizedContent(sql, matcher.end() - 1);
            assertThat(valueCount(values))
                .describedAs("%s 第 %d 条种子数据的取值个数应等于建表列数 %d：%s", table, rows + 1, columnCount, values)
                .isEqualTo(columnCount);
            rows++;
        }
        assertThat(rows).describedAs("初始化 SQL 中缺少 %s 的种子数据", table).isPositive();
    }

    /**
     * 从开括号位置提取括号内的完整文本，忽略引号内的括号。
     */
    private static String parenthesizedContent(String text, int openIndex) {
        int depth = 0;
        char quote = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex + 1, i);
                }
            }
        }
        throw new IllegalStateException("初始化 SQL 的 values 括号不完整");
    }

    /**
     * 统计 values 列表中顶层逗号分隔的取值个数，忽略引号与嵌套括号内的逗号。
     */
    private static int valueCount(String values) {
        int count = 1;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < values.length(); i++) {
            char c = values.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 把 Java 驼峰字段名转换为对应的 SQL 列名。
     */
    private static String toSnakeCase(String fieldName) {
        StringBuilder column = new StringBuilder();
        for (char c : fieldName.toCharArray()) {
            if (Character.isUpperCase(c)) {
                column.append('_').append(Character.toLowerCase(c));
            } else {
                column.append(c);
            }
        }
        return column.toString();
    }

}
