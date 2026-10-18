package org.dromara.system.constant;

/**
 * 员工同步约定，与具体组织信息来源无关；是否在职由来源适配器解释。
 */
public final class UserSyncConstants {
    /** 部门级同步锁前缀。 */
    public static final String LOCK_PREFIX = "system:user:sync:";
    /** 新用户初始密码参数键。 */
    public static final String INIT_PASSWORD_KEY = "sys.user.initPassword";
    /** 跳过该人员。 */
    public static final String SKIPPED = "SKIPPED";
    /** 字段校验失败已置空。 */
    public static final String FIELD_CLEARED = "FIELD_CLEARED";
    /** 本地已绑定但本次外部来源未返回。 */
    public static final String NOT_RETURNED = "NOT_RETURNED";
    /** 已绑定同名本地账号。 */
    public static final String BOUND = "BOUND";
    /** 已提交但缓存清理失败。 */
    public static final String CACHE_REFRESH_FAILED = "CACHE_REFRESH_FAILED";

    private UserSyncConstants() {
    }
}
