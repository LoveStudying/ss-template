package org.dromara.system.sync;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.bo.SysDeptBo;
import org.dromara.system.domain.sync.OrgDepartment;
import org.dromara.system.domain.sync.OrgDepartmentSnapshot;
import org.dromara.system.domain.sync.OrgSubcompany;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.OrgSyncSource;
import org.dromara.system.service.impl.DeptBindingValidator;
import org.dromara.system.service.impl.OrgSyncSourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证部门选填组织来源绑定的校验与快照写入规则。
 */
@DisplayName("部门组织来源绑定校验测试")
class DeptBindingValidatorTest {
    private final SysDeptMapper mapper = mock(SysDeptMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final OrgSyncSource source = mock(OrgSyncSource.class);
    private final OrgSyncSourceProvider provider = mock(OrgSyncSourceProvider.class);
    private final DeptBindingValidator validator = new DeptBindingValidator(mapper, users, provider);

    @BeforeEach
    void enableSource() {
        when(provider.find()).thenReturn(Optional.of(source));
    }

    static SysDept dept(long id, String type, String businessId) {
        SysDept d = new SysDept();
        d.setDeptId(id);
        d.setDeptType(type);
        d.setBusinessId(businessId);
        d.setHrmSubcompanyId("5");
        d.setParentId(0L);
        d.setAncestors("0");
        d.setStatus("0");
        d.setDelFlag("0");
        d.setDeptName("本地" + id);
        d.setOrderNum(0);
        return d;
    }

    static OrgSubcompany company(String id) {
        return new OrgSubcompany(id, "0", "分部", "", false, "", null);
    }

    static OrgDepartment node(String id, String parent, String rawStatus) {
        return new OrgDepartment(id, "5", parent, "部门" + id, "全称" + id, "", 0, "1".equals(rawStatus), rawStatus, null);
    }

    static OrgDepartmentSnapshot snapshot(OrgDepartment... nodes) {
        return new OrgDepartmentSnapshot("5", Arrays.asList(nodes));
    }

    static SysDeptBo binding(String type, String businessId) {
        SysDeptBo input = new SysDeptBo();
        input.setDeptType(type);
        input.setBusinessId(businessId);
        return input;
    }

    @Test
    void omittedBindingFieldsPreserveExistingBinding() {
        SysDept before = dept(10, "department", "1");
        before.setHrmCanceled("1");
        before.setHrmCode("原码");
        before.setLastSyncTime(LocalDateTime.of(2026, 10, 13, 10, 0));
        SysDept target = new SysDept();
        target.setParentId(0L);
        target.setDeptName("新名");
        validator.validateAndApply(new SysDeptBo(), before, target);
        assertThat(target.getBusinessId()).isEqualTo("1");
        assertThat(target.getDeptType()).isEqualTo("department");
        assertThat(target.getHrmCanceled()).isEqualTo("1");
        assertThat(target.getHrmCode()).isEqualTo("原码");
        assertThat(target.getHrmSubcompanyId()).isEqualTo("5");
        assertThat(target.getLastSyncTime()).isEqualTo(before.getLastSyncTime());
        assertThat(target.getDeptName()).isEqualTo("新名");
        verifyNoInteractions(source);
    }

    @Test
    void legacyBaseEditWorksAndUnsupportedTypeIsRejected() {
        validator.validateAndApply(new SysDeptBo(), dept(10, null, null), new SysDept());
        assertThatThrownBy(() -> validator.validateAndApply(binding("other", "1"), null, new SysDept()))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void createsPlainDeptWithoutCallingSource() {
        SysDept target = new SysDept();
        target.setHrmCode("脏值");
        validator.validateAndApply(new SysDeptBo(), null, target);
        assertThat(target.getDeptType()).isNull();
        assertThat(target.getBusinessId()).isNull();
        assertThat(target.getHrmCode()).isNull();
        verifyNoInteractions(source);
    }

    @Test
    void blankBindingIsNormalizedToNull() {
        SysDept target = new SysDept();
        target.setDeptType("");
        target.setBusinessId("  ");
        validator.validateAndApply(binding("", "  "), null, target);
        assertThat(target.getDeptType()).isNull();
        assertThat(target.getBusinessId()).isNull();
        verifyNoInteractions(source);
    }

    @Test
    void blankBindingOnUnboundUpdateKeepsNull() {
        SysDept before = dept(10, null, null);
        SysDept target = dept(10, "", "  ");
        validator.validateAndApply(binding(" ", ""), before, target);
        assertThat(target.getDeptType()).isNull();
        assertThat(target.getBusinessId()).isNull();
        verifyNoInteractions(source);
    }

    @Test
    void rejectsHalfFilledBinding() {
        assertThatThrownBy(() -> validator.validateAndApply(binding("subcompany", null), null, new SysDept()))
            .isInstanceOf(ServiceException.class).hasMessage("部门类型与业务 ID 需同时填写");
        assertThatThrownBy(() -> validator.validateAndApply(binding(" ", "5"), dept(10, null, null), new SysDept()))
            .isInstanceOf(ServiceException.class).hasMessage("部门类型与业务 ID 需同时填写");
        verifyNoInteractions(source);
    }

    @Test
    void noneModeStoresBindingWithoutRemoteCheck() {
        when(provider.find()).thenReturn(Optional.empty());
        SysDept target = new SysDept();
        target.setHrmCode("脏值");
        validator.validateAndApply(binding("subcompany", "5"), null, target);
        assertThat(target.getDeptType()).isEqualTo("subcompany");
        assertThat(target.getBusinessId()).isEqualTo("5");
        assertThat(target.getHrmSubcompanyId()).isNull();
        assertThat(target.getHrmCode()).isNull();
        verifyNoInteractions(source);
    }

    @Test
    void noneModeStillRejectsDuplicateIdentity() {
        when(provider.find()).thenReturn(Optional.empty());
        when(mapper.selectIdentityRows(List.of("5"))).thenReturn(List.of(dept(20, "subcompany", "5")));
        assertThatThrownBy(() -> validator.validateAndApply(binding("subcompany", "5"), null, new SysDept()))
            .isInstanceOf(ServiceException.class).hasMessage("组织来源业务身份已绑定");
        assertThatThrownBy(() -> validator.validateAndApply(binding("subcompany", "05x"), null, new SysDept()))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void deletedIdentityConflictsAndDifferentTypesDoNot() {
        SysDept deleted = dept(20, "subcompany", "5");
        deleted.setDelFlag("1");
        when(mapper.selectIdentityRows(List.of("5"))).thenReturn(List.of(deleted));
        assertThatThrownBy(() -> validator.validateAndApply(binding("subcompany", "5"), null, new SysDept()))
            .isInstanceOf(ServiceException.class).hasMessage("组织来源业务身份被已删除部门占用");
        when(mapper.selectIdentityRows(List.of("5"))).thenReturn(List.of(dept(21, "department", "5")));
        LocalDateTime changed = LocalDateTime.of(2026, 10, 1, 8, 0);
        when(source.listSubcompanies()).thenReturn(Arrays.asList(null,
            new OrgSubcompany("5", "3", "分部全称", "C5", false, "0", changed)));
        SysDept target = new SysDept();
        validator.validateAndApply(binding("subcompany", "5"), null, target);
        assertThat(target.getBusinessId()).isEqualTo("5");
        assertThat(target.getHrmSubcompanyId()).isEqualTo("5");
        assertThat(target.getHrmSupsubcompanyId()).isEqualTo("3");
        assertThat(target.getHrmSupdepartmentId()).isNull();
        assertThat(target.getHrmCode()).isEqualTo("C5");
        assertThat(target.getHrmFullName()).isEqualTo("分部全称");
        assertThat(target.getHrmCanceled()).isEqualTo("0");
        assertThat(target.getHrmLastChangeTime()).isEqualTo(changed);
    }

    @Test
    void subcompanyMustMatchExactlyOnce() {
        when(source.listSubcompanies()).thenReturn(List.of(company("6")));
        assertThatThrownBy(() -> validator.validateAndApply(binding("subcompany", "5"), null, new SysDept()))
            .isInstanceOf(ServiceException.class).hasMessage("组织来源分部不存在或重复");
        when(source.listSubcompanies()).thenReturn(List.of(company("5"), company("5")));
        assertThatThrownBy(() -> validator.validateAndApply(binding("subcompany", "5"), null, new SysDept()))
            .isInstanceOf(ServiceException.class).hasMessage("组织来源分部不存在或重复");
    }

    @Test
    void derivesCompanyFromAncestorAndValidatesExactSourceParent() {
        SysDept company = dept(100, "subcompany", "5");
        when(mapper.selectById(100L)).thenReturn(company);
        when(source.listDepartments("5")).thenReturn(snapshot(node("1", "0", "1")));
        SysDeptBo input = binding("department", " 1 ");
        SysDept target = new SysDept();
        target.setParentId(100L);
        target.setStatus("0");
        validator.validateAndApply(input, null, target);
        assertThat(target.getBusinessId()).isEqualTo("1");
        assertThat(target.getHrmSubcompanyId()).isEqualTo("5");
        assertThat(target.getHrmSupdepartmentId()).isEqualTo("0");
        assertThat(target.getHrmSupsubcompanyId()).isNull();
        assertThat(target.getHrmCanceled()).isEqualTo("1");
        assertThat(target.getStatus()).isEqualTo("0");
        target.setParentId(999L);
        assertThatThrownBy(() -> validator.validateAndApply(input, null, target)).isInstanceOf(ServiceException.class);
    }

    @Test
    void missingDepartmentIsRejected() {
        when(mapper.selectById(100L)).thenReturn(dept(100, "subcompany", "5"));
        when(source.listDepartments("5")).thenReturn(snapshot(node("2", "0", "0")));
        SysDept target = new SysDept();
        target.setParentId(100L);
        assertThatThrownBy(() -> validator.validateAndApply(binding("department", "1"), null, target))
            .isInstanceOf(ServiceException.class).hasMessage("组织来源部门不存在或不属于该分部");
    }

    @Test
    void syncedNodesCannotRebindAndFailedQueryCannotSave() {
        SysDept before = dept(10, "subcompany", "5");
        before.setLastSyncTime(LocalDateTime.now());
        SysDeptBo input = binding("subcompany", "6");
        assertThatThrownBy(() -> validator.validateAndApply(input, before, new SysDept())).isInstanceOf(ServiceException.class);
        before.setLastSyncTime(null);
        when(source.listSubcompanies()).thenThrow(new ServiceException("查询失败"));
        assertThatThrownBy(() -> validator.validateAndApply(input, before, new SysDept()))
            .isInstanceOf(ServiceException.class).hasMessage("查询失败");
    }

    @Test
    void existingUsersPreventRebindingButDoNotPreventFirstBinding() {
        SysDept before = dept(10, "subcompany", "5");
        SysDeptBo input = binding("subcompany", "6");
        when(users.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> validator.validateAndApply(input, before, dept(10, "subcompany", "5")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("禁止重绑");
        before.setDeptType(null);
        before.setBusinessId(null);
        when(source.listSubcompanies()).thenReturn(List.of(company("6")));
        SysDept target = dept(10, null, null);
        validator.validateAndApply(input, before, target);
        assertThat(target.getBusinessId()).isEqualTo("6");
    }

    @Test
    void childrenPreventCorrectionAndFirstBindingChecksDescendantCompany() {
        SysDept before = dept(10, "subcompany", "5");
        SysDept child = dept(11, "department", "1");
        child.setParentId(10L);
        child.setAncestors("0,10");
        when(mapper.selectSourceRelatedRows(List.of(10L))).thenReturn(List.of(before, child));
        SysDeptBo input = binding("subcompany", "6");
        assertThatThrownBy(() -> validator.validateAndApply(input, before, dept(10, "subcompany", "5")))
            .isInstanceOf(ServiceException.class);
        before.setDeptType(null);
        before.setBusinessId(null);
        when(source.listSubcompanies()).thenReturn(List.of(company("6")));
        assertThatThrownBy(() -> validator.validateAndApply(input, before, dept(10, null, null)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("后代");
    }

    @Test
    void cannotMoveBoundDepartmentToUnrelatedSourceParent() {
        SysDept company = dept(100, "subcompany", "5");
        when(mapper.selectById(100L)).thenReturn(company);
        SysDept parent = dept(20, "department", "2");
        parent.setParentId(100L);
        parent.setAncestors("0,100");
        when(mapper.selectById(20L)).thenReturn(parent);
        when(source.listDepartments("5")).thenReturn(snapshot(node("1", "0", "0")));
        SysDept before = dept(10, "department", "1");
        before.setParentId(100L);
        SysDept target = dept(10, "department", "1");
        target.setParentId(20L);
        assertThatThrownBy(() -> validator.validateAndApply(new SysDeptBo(), before, target))
            .isInstanceOf(ServiceException.class).hasMessage("本地上级与组织来源关系不一致，请同步上级");
    }

    @Test
    void subcompanyCannotMoveUnderItsOwnDescendant() {
        SysDept before = dept(10, "subcompany", "5");
        SysDept nested = dept(11, null, null);
        nested.setParentId(10L);
        nested.setAncestors("0,10");
        when(mapper.selectById(11L)).thenReturn(nested);
        when(mapper.selectById(10L)).thenReturn(before);
        SysDept target = dept(10, "subcompany", "5");
        target.setParentId(11L);
        assertThatThrownBy(() -> validator.validateAndApply(new SysDeptBo(), before, target))
            .isInstanceOf(ServiceException.class).hasMessageContaining("环");
        verifyNoInteractions(source);
    }

    @Test
    void firstBindingAllowsAnIndependentNestedSubcompanyWithoutChangingItsOwnership() {
        SysDept before = dept(10, null, null);
        SysDept nested = dept(11, "subcompany", "6");
        nested.setHrmSubcompanyId("6");
        nested.setParentId(10L);
        nested.setAncestors("0,10");
        SysDept child = dept(12, "department", "1");
        child.setParentId(11L);
        child.setAncestors("0,10,11");
        child.setHrmSubcompanyId("6");
        when(mapper.selectById(11L)).thenReturn(nested);
        when(mapper.selectSourceRelatedRows(List.of(10L))).thenReturn(List.of(before, nested, child));
        when(source.listSubcompanies()).thenReturn(List.of(company("5")));
        SysDept target = dept(10, null, null);
        validator.validateAndApply(binding("subcompany", "5"), before, target);
        assertThat(target.getBusinessId()).isEqualTo("5");
        assertThat(nested.getHrmSubcompanyId()).isEqualTo("6");
        assertThat(child.getHrmSubcompanyId()).isEqualTo("6");
    }
}
