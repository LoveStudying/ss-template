package org.dromara.system.domain.sync;

import org.dromara.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 已校验的单个分部完整部门快照，部门按父先子后的拓扑顺序排列。
 *
 * @param subcompanyId 分部业务 ID
 * @param departments  该分部全部部门，不可变
 */
public record OrgDepartmentSnapshot(String subcompanyId, List<OrgDepartment> departments) {

    /** 保存不可变的拓扑顺序。 */
    public OrgDepartmentSnapshot {
        departments = List.copyOf(departments);
    }

    /**
     * 返回指定部门的全部后代，不包含锚点本身，保留拓扑顺序。
     *
     * @param id 锚点部门业务 ID
     * @return 后代部门列表
     * @throws ServiceException 锚点不在快照中
     */
    public List<OrgDepartment> descendants(String id) {
        if (departments.stream().noneMatch(d -> d.id().equals(id))) {
            throw new ServiceException("组织锚点不存在");
        }
        Set<String> selected = new HashSet<>();
        selected.add(id);
        List<OrgDepartment> result = new ArrayList<>();
        for (OrgDepartment department : departments) {
            if (selected.contains(department.parentId())) {
                selected.add(department.id());
                result.add(department);
            }
        }
        return List.copyOf(result);
    }
}
