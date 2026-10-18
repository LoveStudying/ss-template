package org.dromara.system.sync;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.hrm.client.HrmClient;
import org.dromara.common.hrm.client.HrmClientException;
import org.dromara.common.hrm.domain.HrmQueryResult;
import org.dromara.common.hrm.domain.HrmRecord;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.dromara.system.domain.sync.OrgDepartment;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.sync.OrgSubcompany;
import org.dromara.system.service.impl.HrmOrgSyncSource;
import org.dromara.system.service.impl.OrgSnapshotValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 HRM 原始记录到中性组织模型的映射，样例数据已脱敏。
 */
@DisplayName("HRM 组织信息来源测试")
class HrmOrgSyncSourceTest {

    private final HrmClient client = mock(HrmClient.class);
    private final HrmOrgSyncSource source = new HrmOrgSyncSource(client, new OrgSnapshotValidator());

    /** 读取样例 JSON，按 HrmClient 的输出形态构造记录。 */
    static List<HrmRecord> load(String path) throws Exception {
        var json = JsonMapper.builder().build().readTree(HrmOrgSyncSourceTest.class.getResourceAsStream(path));
        List<HrmRecord> records = new ArrayList<>();
        json.forEach(row -> {
            Map<String, String> fields = new HashMap<>();
            row.properties().forEach(e -> fields.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
            records.add(new HrmRecord(fields));
        });
        return records;
    }

    static HrmRecord dept(String id, String parent, String canceled) {
        Map<String, String> f = new HashMap<>();
        f.put("_departmentid", id); f.put("_supdepartmentid", parent);
        f.put("_subcompanyid", "5"); f.put("_shortname", "部门" + id);
        f.put("_fullname", "全称" + id); f.put("_canceled", canceled);
        f.put("_showorder", ""); f.put("_code", ""); f.put("lastChangdate", "");
        return new HrmRecord(f);
    }

    static HrmRecord with(HrmRecord record, String key, String value) {
        Map<String, String> f = new HashMap<>(record.fields());
        f.put(key, value);
        return new HrmRecord(f);
    }

    static HrmQueryResult result(String method, HrmRecord... records) {
        return new HrmQueryResult(method, false, Arrays.asList(records));
    }

    static HrmRecord user(String userId, String status) {
        Map<String, String> f = new HashMap<>();
        f.put("userid", userId); f.put("loginid", " u" + userId + " "); f.put("lastname", "张三");
        f.put("status", status); f.put("departmentid", "1885"); f.put("subcompanyid1", "48");
        f.put("sex", "男"); f.put("mobile", "13800000000"); f.put("email", "user1@example.test");
        f.put("workcode", ""); f.put("lastChangdate", "2025-09-01");
        return new HrmRecord(f);
    }

    @Test
    void sourceIsHrm() {
        assertThat(source.source()).isEqualTo("hrm");
    }

    @Test
    void mapsSubcompany5Snapshot() throws Exception {
        when(client.getHrmDepartmentInfo("5"))
            .thenReturn(new HrmQueryResult("getHrmDepartmentInfo", false, load("/hrm/departments-subcompany-5.json")));
        var snapshot = source.listDepartments("5");
        assertThat(snapshot.subcompanyId()).isEqualTo("5");
        assertThat(snapshot.departments()).hasSize(983);
        assertThat(snapshot.descendants("2976")).hasSize(25).noneMatch(d -> d.id().equals("2976"));
        OrgDepartment canceled = snapshot.departments().stream().filter(d -> "1".equals(d.rawStatus())).findFirst().orElseThrow();
        assertThat(canceled.disabled()).isTrue();
        OrgDepartment open = snapshot.departments().stream().filter(d -> "".equals(d.rawStatus())).findFirst().orElseThrow();
        assertThat(open.disabled()).isFalse();
        assertThat(snapshot.departments().stream().filter(OrgDepartment::disabled)).hasSize(782);
        OrgDepartment first = snapshot.departments().stream().filter(d -> d.id().equals("2")).findFirst().orElseThrow();
        assertThat(first.name()).isEqualTo("总经办");
        assertThat(first.fullName()).isEqualTo("总经办-国健");
        assertThat(first.code()).isEqualTo("D0002");
        assertThat(first.orderNum()).isEqualTo(1);
        assertThat(first.lastChangeTime()).isEqualTo(LocalDateTime.of(2018, 7, 2, 14, 1, 36));
    }

    @Test
    void departmentNullResultFails() {
        when(client.getHrmDepartmentInfo("5")).thenReturn(new HrmQueryResult("getHrmDepartmentInfo", true, List.of()));
        assertThatThrownBy(() -> source.listDepartments("5")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("查询结果为空");
    }

    @Test
    void departmentNullRecordFails() {
        when(client.getHrmDepartmentInfo("5")).thenReturn(result("getHrmDepartmentInfo", (HrmRecord) null));
        assertThatThrownBy(() -> source.listDepartments("5")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("空记录");
    }

    @Test
    void rejectsUnknownCanceled() {
        when(client.getHrmDepartmentInfo("5")).thenReturn(result("getHrmDepartmentInfo", dept("1", "0", "2")));
        assertThatThrownBy(() -> source.listDepartments("5")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("状态缺失或未知");
        when(client.getHrmDepartmentInfo("5")).thenReturn(result("getHrmDepartmentInfo", dept("1", "0", null)));
        assertThatThrownBy(() -> source.listDepartments("5")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("状态缺失或未知");
    }

    @Test
    void rejectsInvalidOrderAndTime() {
        for (HrmRecord bad : List.of(with(dept("1", "0", ""), "_showorder", null),
            with(dept("1", "0", ""), "_showorder", "x"), with(dept("1", "0", ""), "_showorder", "2147483648"),
            with(dept("1", "0", ""), "lastChangdate", "2026-02-30 12:00:00"))) {
            when(client.getHrmDepartmentInfo("5")).thenReturn(result("getHrmDepartmentInfo", bad));
            assertThatThrownBy(() -> source.listDepartments("5")).isInstanceOf(ServiceException.class)
                .hasMessageContaining("排序或日期非法");
        }
    }

    @Test
    void nameFallsBackToFullName() {
        when(client.getHrmDepartmentInfo("5"))
            .thenReturn(result("getHrmDepartmentInfo", with(dept("1", "0", ""), "_shortname", " ")));
        assertThat(source.listDepartments("5").departments().getFirst().name()).isEqualTo("全称1");
        HrmRecord noName = with(with(dept("1", "0", ""), "_shortname", null), "_fullname", "");
        when(client.getHrmDepartmentInfo("5")).thenReturn(result("getHrmDepartmentInfo", noName));
        assertThatThrownBy(() -> source.listDepartments("5")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("名称为空");
    }

    @Test
    void emptyShowOrderIsZero() {
        when(client.getHrmDepartmentInfo("5")).thenReturn(result("getHrmDepartmentInfo", dept("01", "0", "")));
        OrgDepartment d = source.listDepartments("5").departments().getFirst();
        assertThat(d.id()).isEqualTo("01");
        assertThat(d.orderNum()).isZero();
    }

    @Test
    void emptyLastChangeIsNull() {
        when(client.getHrmDepartmentInfo("5")).thenReturn(result("getHrmDepartmentInfo", dept("1", "0", "0")));
        OrgDepartment d = source.listDepartments("5").departments().getFirst();
        assertThat(d.lastChangeTime()).isNull();
        assertThat(d.disabled()).isFalse();
        assertThat(d.rawStatus()).isEqualTo("0");
    }

    @Test
    void mapsSubcompanies() {
        Map<String, String> f = new HashMap<>();
        f.put("_subcompanyid", "5"); f.put("_supsubcompanyid", "0"); f.put("_fullname", "国健");
        f.put("_code", "S05"); f.put("_canceled", "1"); f.put("lastChangdate", "2024-01-02 03:04:05");
        Map<String, String> g = new HashMap<>(f);
        g.put("_subcompanyid", "6"); g.put("_canceled", ""); g.put("lastChangdate", "");
        when(client.getHrmSubcompanyInfo()).thenReturn(result("getHrmSubcompanyInfo", new HrmRecord(f), new HrmRecord(g)));
        List<OrgSubcompany> list = source.listSubcompanies();
        assertThat(list).containsExactly(
            new OrgSubcompany("5", "0", "国健", "S05", true, "1", LocalDateTime.of(2024, 1, 2, 3, 4, 5)),
            new OrgSubcompany("6", "0", "国健", "S05", false, "", null));
    }

    @Test
    void subcompanyErrors() {
        when(client.getHrmSubcompanyInfo()).thenReturn(new HrmQueryResult("getHrmSubcompanyInfo", true, List.of()));
        assertThatThrownBy(source::listSubcompanies).isInstanceOf(ServiceException.class);
        Map<String, String> f = new HashMap<>();
        f.put("_subcompanyid", "5"); f.put("_supsubcompanyid", "0"); f.put("_fullname", "国健");
        f.put("_code", ""); f.put("_canceled", "9"); f.put("lastChangdate", "");
        when(client.getHrmSubcompanyInfo()).thenReturn(result("getHrmSubcompanyInfo", new HrmRecord(f)));
        assertThatThrownBy(source::listSubcompanies).isInstanceOf(ServiceException.class)
            .hasMessageContaining("状态缺失或未知");
        f.put("_canceled", ""); f.put("_supsubcompanyid", "x");
        when(client.getHrmSubcompanyInfo()).thenReturn(result("getHrmSubcompanyInfo", new HrmRecord(f)));
        assertThatThrownBy(source::listSubcompanies).isInstanceOf(ServiceException.class);
        when(client.getHrmSubcompanyInfo()).thenReturn(result("getHrmSubcompanyInfo", (HrmRecord) null));
        assertThatThrownBy(source::listSubcompanies).isInstanceOf(ServiceException.class)
            .hasMessageContaining("空记录");
    }

    @Test
    void userNullResultIsEmpty() {
        when(client.getHrmUserInfo(any())).thenReturn(new HrmQueryResult("getHrmUserInfo", true, List.of()));
        assertThat(source.listEmployees("1885")).isEmpty();
    }

    @Test
    void mapsEmployees() throws Exception {
        when(client.getHrmUserInfo(any()))
            .thenReturn(new HrmQueryResult("getHrmUserInfo", false, load("/hrm/users-department-1885.json")));
        List<OrgEmployee> list = source.listEmployees("1885");
        ArgumentCaptor<HrmUserQuery> query = ArgumentCaptor.forClass(HrmUserQuery.class);
        verify(client).getHrmUserInfo(query.capture());
        assertThat(query.getValue().getDepartmentId()).isEqualTo("1885");
        assertThat(list).hasSize(4);
        OrgEmployee first = list.getFirst();
        assertThat(first.gender()).isEqualTo("0");
        assertThat(first.rawStatus()).isEqualTo("5");
        assertThat(first.active()).isFalse();
        assertThat(first.loginId()).isNull();
        assertThat(first.workcode()).isNull();
        OrgEmployee female = list.get(1);
        assertThat(female.gender()).isEqualTo("1");
        assertThat(female.email()).isNull();
        OrgEmployee active = list.stream().filter(e -> e.id().equals("538233")).findFirst().orElseThrow();
        assertThat(active.active()).isTrue();
        assertThat(active.rawStatus()).isEqualTo("1");
        assertThat(active.gender()).isEqualTo("1");
        assertThat(active.loginId()).isEqualTo("testuser04");
        assertThat(active.workcode()).isEqualTo("910052");
        assertThat(active.name()).isEqualTo("测试员4");
        assertThat(active.email()).isEqualTo("user4@example.test");
        assertThat(active.mobile()).isEqualTo("13800000004");
        assertThat(active.departmentId()).isEqualTo("1885");
        assertThat(active.subcompanyId()).isEqualTo("48");
        assertThat(active.lastChangeDate()).isEqualTo(LocalDate.of(2025, 9, 1));
    }

    @Test
    void mapsEmployeeEdgeCasesWithoutDedup() {
        HrmRecord blankStatus = user("1", "");
        HrmRecord badDate = with(with(user("2", "1"), "lastChangdate", "2025/09/01"), "sex", "");
        HrmRecord duplicate = user("2", "1");
        HrmRecord blankId = user(" ", "1");
        when(client.getHrmUserInfo(any())).thenReturn(new HrmQueryResult("getHrmUserInfo", false,
            Arrays.asList(null, blankStatus, badDate, duplicate, blankId)));
        List<OrgEmployee> list = source.listEmployees("1885");
        assertThat(list).hasSize(4);
        assertThat(list.get(0).rawStatus()).isNull();
        assertThat(list.get(0).active()).isFalse();
        assertThat(list.get(0).loginId()).isEqualTo("u1");
        assertThat(list.get(1).lastChangeDate()).isNull();
        assertThat(list.get(1).gender()).isEqualTo("2");
        assertThat(list.get(2).id()).isEqualTo("2");
        assertThat(list.get(3).id()).isNull();
    }

    @Test
    void clientErrorBecomesServiceException() {
        when(client.getHrmDepartmentInfo("5")).thenThrow(new HrmClientException("HRM 请求超时"));
        assertThatThrownBy(() -> source.listDepartments("5")).isInstanceOf(ServiceException.class)
            .hasMessage("HRM 请求超时");
        when(client.getHrmSubcompanyInfo()).thenThrow(new HrmClientException("HRM 请求超时"));
        assertThatThrownBy(source::listSubcompanies).isInstanceOf(ServiceException.class)
            .hasMessage("HRM 请求超时");
        when(client.getHrmUserInfo(any())).thenThrow(new HrmClientException("HRM 请求超时"));
        assertThatThrownBy(() -> source.listEmployees("1885")).isInstanceOf(ServiceException.class)
            .hasMessage("HRM 请求超时");
    }

    @Test
    void isActiveStatus() {
        assertThat(source.isActiveStatus("1")).isTrue();
        assertThat(source.isActiveStatus("5")).isFalse();
        assertThat(source.isActiveStatus(null)).isFalse();
    }
}
