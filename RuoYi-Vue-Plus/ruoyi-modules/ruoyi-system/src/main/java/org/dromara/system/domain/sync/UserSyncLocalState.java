package org.dromara.system.domain.sync;

import org.dromara.system.domain.SysUser;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工同步前读取的本地用户状态，均为未删除用户。
 *
 * @param byHrmUserId   按 hrm_user_id（外部人员 ID）分组的匹配候选
 * @param byUserName    按登录名比较键（不区分大小写）分组的同名候选
 * @param deptBound     当前部门下已绑定外部人员的用户
 * @param scopedUserIds 候选中当前操作人数据权限可见的用户 ID
 */
public record UserSyncLocalState(Map<String, List<SysUser>> byHrmUserId, Map<String, List<SysUser>> byUserName,
                                 List<SysUser> deptBound, Set<Long> scopedUserIds) {
}
