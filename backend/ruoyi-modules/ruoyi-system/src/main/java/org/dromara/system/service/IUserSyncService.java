package org.dromara.system.service;

import org.dromara.system.domain.vo.UserSyncResultVo;

/**
 * 按部门从组织信息来源同步员工。
 */
public interface IUserSyncService {

    /**
     * 按部门绑定的外部部门 ID 拉取员工并写入本地用户。
     *
     * @param deptId 本地普通部门 ID
     * @return 同步统计与提示
     */
    UserSyncResultVo syncDepartment(Long deptId);
}
