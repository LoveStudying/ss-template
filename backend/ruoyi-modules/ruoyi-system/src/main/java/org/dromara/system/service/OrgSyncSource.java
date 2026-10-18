package org.dromara.system.service;

import org.dromara.system.domain.sync.OrgDepartmentSnapshot;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.sync.OrgSubcompany;

import java.util.List;

/**
 * 组织信息来源。同步与绑定逻辑只依赖本接口和中性模型，来源格式细节由实现类负责。
 */
public interface OrgSyncSource {

    /**
     * 来源标识，与 org-sync.source 一致。
     *
     * @return 来源标识
     */
    String source();

    /**
     * 查询全部分部。
     *
     * @return 分部列表
     */
    List<OrgSubcompany> listSubcompanies();

    /**
     * 查询分部的完整部门快照，已经 OrgSnapshotValidator 校验并按父先子后排序；外部 null 结果抛 ServiceException。
     *
     * @param subcompanyId 分部业务 ID
     * @return 部门快照
     */
    OrgDepartmentSnapshot listDepartments(String subcompanyId);

    /**
     * 查询部门下的员工；外部 null 结果返回空列表，null 元素丢弃，不做去重（去重在通用层）。
     *
     * @param departmentId 部门业务 ID
     * @return 员工列表
     */
    List<OrgEmployee> listEmployees(String departmentId);

    /**
     * 把本地已存的原始员工状态（sys_user.hrm_status）翻译为是否在职。
     *
     * @param rawStatus 原始状态，null 返回 false
     * @return 是否在职
     */
    boolean isActiveStatus(String rawStatus);
}
