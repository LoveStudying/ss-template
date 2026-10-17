package org.dromara.system.sync;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.sync.OrgDepartment;
import org.dromara.system.domain.sync.OrgDepartmentSnapshot;
import org.dromara.system.service.impl.OrgSnapshotValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证与来源格式无关的部门快照校验：ID、重复、跨分部、缺父、环、字段长度与拓扑排序。
 */
@DisplayName("组织部门快照校验测试")
class OrgSnapshotValidatorTest {

    private final OrgSnapshotValidator validator = new OrgSnapshotValidator();

    /** 构造分部 5 下的部门，名称与全称均合法。 */
    static OrgDepartment dept(String id, String parent) {
        return dept(id, "5", parent, "部门" + id, 0);
    }

    static OrgDepartment dept(String id, String company, String parent, String name, int order) {
        return new OrgDepartment(id, company, parent, name, "全称" + id, "", order, false, "", null);
    }

    static OrgDepartmentSnapshot validate(OrgDepartment... departments) {
        return new OrgSnapshotValidator().validate("5", Arrays.asList(departments));
    }

    @Test
    void acceptsEmptyList() {
        assertThat(validator.validate("5", List.of()).departments()).isEmpty();
    }

    @Test
    void rejectsDuplicateId() {
        assertThatThrownBy(() -> validate(dept("1", "0"), dept("1", "0")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("业务 ID 重复");
    }

    @Test
    void rejectsMissingParent() {
        assertThatThrownBy(() -> validate(dept("1", "9")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("上级不存在");
    }

    @Test
    void rejectsCycles() {
        assertThatThrownBy(() -> validate(dept("1", "1"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> validate(dept("1", "2"), dept("2", "1")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("环");
    }

    @Test
    void rejectsCrossSubcompany() {
        assertThatThrownBy(() -> validate(dept("1", "6", "0", "部门1", 0)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("跨分部");
    }

    @Test
    void rejectsInvalidIds() {
        for (String id : Arrays.asList(null, "", "0", "00", "a1", " 1", "1".repeat(65))) {
            assertThatThrownBy(() -> validate(dept(id, "0"))).isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> validate(dept("1", "00"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> validator.validate("0", List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrgSnapshotValidator.validateId("0", false)).isInstanceOf(ServiceException.class);
        OrgSnapshotValidator.validateId("0", true);
        OrgSnapshotValidator.validateId("01", false);
    }

    @Test
    void rejectsOverlongFields() {
        assertThatThrownBy(() -> validate(dept("1", "5", "0", "长".repeat(31), 0)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("字段超长");
        OrgDepartment longFull = new OrgDepartment("1", "5", "0", "部门", "全".repeat(201), "", 0, false, "", null);
        assertThatThrownBy(() -> validate(longFull)).isInstanceOf(ServiceException.class);
        OrgDepartment longCode = new OrgDepartment("1", "5", "0", "部门", "全称", "x".repeat(65), 0, false, "", null);
        assertThatThrownBy(() -> validate(longCode)).isInstanceOf(ServiceException.class);
        OrgDepartment blankName = new OrgDepartment("1", "5", "0", " ", "全称", "", 0, false, "", null);
        assertThatThrownBy(() -> validate(blankName)).isInstanceOf(ServiceException.class);
        assertThat(validate(dept("1", "5", "0", "长".repeat(30), 0)).departments()).hasSize(1);
    }

    @Test
    void ordersShuffledTreeParentFirst() {
        var snapshot = validate(dept("6", "5"), dept("2", "1"), dept("4", "3"),
            dept("1", "0"), dept("5", "4"), dept("3", "2"));
        assertThat(snapshot.departments()).extracting(OrgDepartment::id)
            .containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void ordersSiblingsByOrderNumThenId() {
        var snapshot = validate(dept("3", "5", "0", "c", 1), dept("2", "5", "0", "b", 2),
            dept("1", "5", "0", "a", 1));
        assertThat(snapshot.departments()).extracting(OrgDepartment::id).containsExactly("1", "3", "2");
    }

    @Test
    void descendantsExcludeAnchorAndRejectUnknownAnchor() {
        var snapshot = validate(dept("1", "0"), dept("2", "1"), dept("3", "2"), dept("4", "0"));
        assertThat(snapshot.descendants("1")).extracting(OrgDepartment::id).containsExactly("2", "3");
        assertThat(snapshot.descendants("3")).isEmpty();
        assertThatThrownBy(() -> snapshot.descendants("9")).isInstanceOf(ServiceException.class);
    }
}
