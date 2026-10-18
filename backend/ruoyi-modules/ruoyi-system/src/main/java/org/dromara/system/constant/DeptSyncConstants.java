package org.dromara.system.constant;

/**
 * 部门组织来源身份与同步状态保护约定，与具体来源无关。
 */
public final class DeptSyncConstants {
    /** 分部类型。 */
    public static final String SUBCOMPANY = "subcompany";
    /** 普通部门类型。 */
    public static final String DEPARTMENT = "department";
    /** 直接用户关联状态保护。 */
    public static final String HAS_USERS = "HAS_USERS";
    /** 正常后代状态保护。 */
    public static final String ACTIVE_DESCENDANT = "ACTIVE_DESCENDANT";
    /** 已提交但缓存刷新失败。 */
    public static final String CACHE_REFRESH_FAILED = "CACHE_REFRESH_FAILED";
    /** 分部互斥锁前缀。 */
    public static final String LOCK_PREFIX = "system:dept:sync:";

    private DeptSyncConstants() {
    }

    /**
     * 判断是否为本期支持的组织类型。
     *
     * @param value 部门类型
     * @return 是否为分部或普通部门
     */
    public static boolean isSupportedType(String value) {
        return SUBCOMPANY.equals(value) || DEPARTMENT.equals(value);
    }
}
