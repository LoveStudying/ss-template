package org.dromara.system.service;

import org.dromara.system.domain.vo.DeptSyncResultVo;

/** 组织来源全部子部门同步应用接口。 */
public interface ISysDeptSyncService {
    /** 同步指定本地节点的组织来源后代，跳过停用节点及其子树，范围由持久化绑定决定。 */
    DeptSyncResultVo syncChildren(Long deptId);
}
