package org.dromara.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.constant.DeptSyncConstants;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.bo.SysDeptBo;
import org.dromara.system.domain.sync.OrgDepartment;
import org.dromara.system.domain.sync.OrgDepartmentSnapshot;
import org.dromara.system.domain.sync.OrgSubcompany;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.OrgSyncSource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 部门组织来源绑定维护的唯一入口，隔离请求字段与后端来源快照。
 * <p>
 * 绑定为选填：未填写时按普通本地部门保存，不调用来源；填写后两项必须同时存在。
 * 未启用来源时只做本地校验并清空来源快照字段，启用来源时额外向来源确认存在与上级关系。
 */
@Component
@RequiredArgsConstructor
public class DeptBindingValidator {
    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final OrgSyncSourceProvider sourceProvider;

    /**
     * 校验新增、首次绑定、纠错或移动，并把归一化后的绑定和来源快照写入 target。
     * 旧记录请求未填写绑定字段时保留其完整快照；空白字符串视同未填写，写入 null。
     *
     * @param input  请求 BO，仅读取 deptType、businessId
     * @param before 修改前的部门，新增时为 null
     * @param target 待保存的部门实体，由本方法覆盖绑定与快照字段
     * @throws ServiceException 绑定不完整、非法、冲突，或来源确认失败
     */
    public void validateAndApply(SysDeptBo input, SysDept before, SysDept target) {
        String inputType = blankToNull(input.getDeptType());
        String inputId = blankToNull(input.getBusinessId());
        if ((inputType == null) != (inputId == null)) {
            throw new ServiceException("部门类型与业务 ID 需同时填写");
        }
        boolean omitted = inputType == null;
        clearBinding(target);
        if (before != null) {
            copyBinding(before, target);
        }
        if (before != null && !Objects.equals(before.getParentId(), target.getParentId())) {
            validateParentChain(before, target);
        }
        if (omitted && (before == null || before.getDeptType() == null
            || Objects.equals(before.getParentId(), target.getParentId()))) {
            return;
        }
        String type = omitted ? before.getDeptType() : inputType;
        String id = omitted ? blankToNull(before.getBusinessId()) : inputId;
        if (!DeptSyncConstants.isSupportedType(type)) {
            throw new ServiceException("请选择部门类型与业务 ID");
        }
        OrgSnapshotValidator.validateId(id, false);
        boolean bound = before != null && before.getDeptType() != null;
        boolean changed = bound && (!Objects.equals(type, before.getDeptType()) || !Objects.equals(id, before.getBusinessId()));
        if (changed && !isEditable(before)) {
            throw new ServiceException("已有用户、子部门或同步记录，禁止重绑");
        }
        for (SysDept row : deptMapper.selectIdentityRows(List.of(id))) {
            if (type.equals(row.getDeptType()) && !Objects.equals(target.getDeptId(), row.getDeptId())) {
                throw new ServiceException("1".equals(row.getDelFlag()) ? "组织来源业务身份被已删除部门占用" : "组织来源业务身份已绑定");
            }
        }
        target.setDeptType(type);
        target.setBusinessId(id);
        Optional<OrgSyncSource> source = sourceProvider.find();
        if (source.isEmpty()) {
            // 未启用来源：无法确认快照，新绑定或改绑时清空来源字段；绑定未变时保留已有快照。
            if (!bound || changed) {
                clearSnapshot(target);
            }
            return;
        }
        if (DeptSyncConstants.SUBCOMPANY.equals(type)) {
            applySubcompany(source.get(), id, target);
        } else {
            applyDepartment(source.get(), id, target);
        }
        if (before != null) {
            for (SysDept child : deptMapper.selectSourceRelatedRows(List.of(before.getDeptId()))) {
                if (isDescendant(child, before.getDeptId()) && child.getDeptType() != null
                    && !Objects.equals(child.getHrmSubcompanyId(), descendantCompany(child, before, target))) {
                    throw new ServiceException("已绑定后代所属分部冲突");
                }
            }
        }
    }

    /**
     * 返回绑定纠错可编辑性；实际保存时再次检查，不能信任前端标志。
     *
     * @param before 修改前的部门
     * @return 未绑定，或无同步记录、无直接用户且无后代时为 true
     */
    public boolean isEditable(SysDept before) {
        if (before.getDeptType() == null) {
            return true;
        }
        if (before.getLastSyncTime() != null || userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
            .eq(SysUser::getDeptId, before.getDeptId())) > 0) {
            return false;
        }
        return deptMapper.selectSourceRelatedRows(List.of(before.getDeptId())).stream()
            .noneMatch(d -> isDescendant(d, before.getDeptId()));
    }

    private void applySubcompany(OrgSyncSource source, String id, SysDept target) {
        List<OrgSubcompany> matches = source.listSubcompanies().stream().filter(Objects::nonNull)
            .filter(c -> id.equals(c.id())).toList();
        if (matches.size() != 1) {
            throw new ServiceException("组织来源分部不存在或重复");
        }
        OrgSubcompany company = matches.getFirst();
        target.setHrmSubcompanyId(company.id());
        target.setHrmSupsubcompanyId(company.parentId());
        target.setHrmSupdepartmentId(null);
        target.setHrmCode(company.code());
        target.setHrmFullName(company.fullName());
        target.setHrmCanceled(company.rawStatus());
        target.setHrmLastChangeTime(company.lastChangeTime());
    }

    private void applyDepartment(OrgSyncSource source, String id, SysDept target) {
        SysDept company = nearestCompany(target.getParentId());
        OrgDepartmentSnapshot snapshot = source.listDepartments(company.getBusinessId());
        OrgDepartment node = snapshot == null ? null : snapshot.departments().stream()
            .filter(d -> d != null && id.equals(d.id())).findFirst().orElse(null);
        if (node == null) {
            throw new ServiceException("组织来源部门不存在或不属于该分部");
        }
        SysDept parent = deptMapper.selectById(target.getParentId());
        String expectedParent = DeptSyncConstants.SUBCOMPANY.equals(parent.getDeptType()) ? "0" : parent.getBusinessId();
        if (!node.parentId().equals(expectedParent)) {
            throw new ServiceException("本地上级与组织来源关系不一致，请同步上级");
        }
        target.setHrmSubcompanyId(node.subcompanyId());
        target.setHrmSupdepartmentId(node.parentId());
        target.setHrmSupsubcompanyId(null);
        target.setHrmCode(node.code());
        target.setHrmFullName(node.fullName());
        target.setHrmCanceled(node.rawStatus());
        target.setHrmLastChangeTime(node.lastChangeTime());
    }

    private void validateParentChain(SysDept before, SysDept target) {
        Long id = target.getParentId();
        Set<Long> seen = new HashSet<>();
        while (id != null && id != 0) {
            if (id.equals(before.getDeptId()) || !seen.add(id)) {
                throw new ServiceException("部门挂载将产生环");
            }
            SysDept parent = deptMapper.selectById(id);
            if (parent == null) {
                throw new ServiceException("父部门不存在");
            }
            id = parent.getParentId();
        }
    }

    private String descendantCompany(SysDept child, SysDept before, SysDept target) {
        if (DeptSyncConstants.SUBCOMPANY.equals(child.getDeptType())) {
            return child.getBusinessId();
        }
        Long id = child.getParentId();
        Set<Long> seen = new HashSet<>();
        while (id != null && id != 0 && seen.add(id)) {
            SysDept parent = id.equals(before.getDeptId()) ? target : deptMapper.selectById(id);
            if (parent == null) {
                break;
            }
            if (DeptSyncConstants.SUBCOMPANY.equals(parent.getDeptType())) {
                return parent.getBusinessId();
            }
            id = parent.getParentId();
        }
        throw new ServiceException("已绑定后代缺少分部祖先");
    }

    private SysDept nearestCompany(Long id) {
        Set<Long> visited = new HashSet<>();
        while (id != null && id != 0 && visited.add(id)) {
            SysDept row = deptMapper.selectById(id);
            if (row == null) {
                break;
            }
            if (DeptSyncConstants.SUBCOMPANY.equals(row.getDeptType()) && row.getBusinessId() != null) {
                return row;
            }
            id = row.getParentId();
        }
        throw new ServiceException("请先创建或绑定分部祖先");
    }

    private boolean isDescendant(SysDept row, Long id) {
        return "0".equals(row.getDelFlag()) && row.getAncestors() != null
            && Arrays.asList(row.getAncestors().split(",")).contains(id.toString());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 清空请求带入的绑定、快照与同步时间，防止空白或伪造值落库。 */
    private static void clearBinding(SysDept target) {
        target.setDeptType(null);
        target.setBusinessId(null);
        target.setLastSyncTime(null);
        clearSnapshot(target);
    }

    /** 清空来源派生的快照字段。 */
    private static void clearSnapshot(SysDept target) {
        target.setHrmSubcompanyId(null);
        target.setHrmSupdepartmentId(null);
        target.setHrmSupsubcompanyId(null);
        target.setHrmCode(null);
        target.setHrmFullName(null);
        target.setHrmCanceled(null);
        target.setHrmLastChangeTime(null);
    }

    private void copyBinding(SysDept before, SysDept target) {
        BeanUtil.copyProperties(before, target, "deptId", "parentId", "ancestors", "deptName", "deptCategory", "orderNum",
            "leader", "phone", "email", "status", "delFlag", "children", "createDept", "createBy", "createTime",
            "updateBy", "updateTime");
    }
}
