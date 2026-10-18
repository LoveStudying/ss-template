package org.dromara.common.hrm.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.hrm.client.HrmClient;
import org.dromara.common.hrm.domain.HrmQueryResult;
import org.dromara.common.hrm.domain.HrmRecord;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.dromara.common.hrm.domain.vo.HrmQueryVo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HrmQueryServiceTest {

    private final HrmClient client = mock(HrmClient.class);
    private final HrmQueryService service = new HrmQueryService(client);

    @Test
    void usersRequireAnOrganizationOrWorkcodeFilter() {
        HrmUserQuery query = new HrmUserQuery();
        query.setWorkcode("  ");

        assertThatThrownBy(() -> service.users(query))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("筛选条件");
        verifyNoInteractions(client);
    }

    @Test
    void usersReturnAllFieldsAndPreserveNullItems() {
        HrmUserQuery query = new HrmUserQuery();
        query.setWorkcode("EMP001");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workcode", "EMP001");
        fields.put("lastname", "测试人员");
        fields.put("departmentid", "20");
        fields.put("mobile", "13800000000");
        fields.put("idcard", "secret-id");
        fields.put("address", "private-address");
        fields.put("customField", "custom-value");
        fields.put("emptyField", "");
        fields.put("nullField", null);
        when(client.getHrmUserInfo(query)).thenReturn(new HrmQueryResult("getHrmUserInfo", false,
            Arrays.asList(null, new HrmRecord(fields))));

        HrmQueryVo response = service.users(query);

        assertThat(response.nullResult()).isFalse();
        assertThat(response.records()).hasSize(2);
        assertThat(response.records().getFirst()).isNull();
        assertThat(response.method()).isEqualTo("getHrmUserInfo");
        assertThat(response.records().get(1)).containsExactlyEntriesOf(fields);
        verify(client).getHrmUserInfo(query);
    }

    @Test
    void usersPreserveNullResultAndEmptyArrayDistinction() {
        HrmUserQuery query = new HrmUserQuery();
        query.setWorkcode("EMP001");
        when(client.getHrmUserInfo(query))
            .thenReturn(new HrmQueryResult("getHrmUserInfo", true, List.of()))
            .thenReturn(new HrmQueryResult("getHrmUserInfo", false, List.of()));

        HrmQueryVo nullResult = service.users(query);
        HrmQueryVo emptyResult = service.users(query);

        assertThat(nullResult.nullResult()).isTrue();
        assertThat(nullResult.records()).isEmpty();
        assertThat(emptyResult.nullResult()).isFalse();
        assertThat(emptyResult.records()).isEmpty();
    }

    @Test
    void organizationQueriesReturnAllFieldsAndPreserveNullItems() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("_fullname", "测试组织");
        fields.put("customField", "custom-value");
        fields.put("emptyField", "");
        fields.put("nullField", null);
        List<HrmRecord> records = Arrays.asList(null, new HrmRecord(fields));
        when(client.getHrmSubcompanyInfo())
            .thenReturn(new HrmQueryResult("getHrmSubcompanyInfo", false, records));
        when(client.getHrmDepartmentInfo("10"))
            .thenReturn(new HrmQueryResult("getHrmDepartmentInfo", false, records));
        when(client.getHrmJobTitleInfo("10", "20"))
            .thenReturn(new HrmQueryResult("getHrmJobTitleInfo", false, records));

        List<HrmQueryVo> responses = List.of(service.subcompanies(), service.departments("10"),
            service.jobTitles("10", "20"));

        assertThat(responses).extracting(HrmQueryVo::method)
            .containsExactly("getHrmSubcompanyInfo", "getHrmDepartmentInfo", "getHrmJobTitleInfo");
        for (HrmQueryVo response : responses) {
            assertThat(response.nullResult()).isFalse();
            assertThat(response.records()).hasSize(2);
            assertThat(response.records().getFirst()).isNull();
            assertThat(response.records().get(1)).containsExactlyEntriesOf(fields);
        }
        verify(client).getHrmSubcompanyInfo();
        verify(client).getHrmDepartmentInfo("10");
        verify(client).getHrmJobTitleInfo("10", "20");
    }

    @Test
    void organizationQueriesPassFiltersAndKeepNullResultDistinct() {
        when(client.getHrmDepartmentInfo("10"))
            .thenReturn(new HrmQueryResult("getHrmDepartmentInfo", true, List.of()));
        when(client.getHrmJobTitleInfo("10", "20"))
            .thenReturn(new HrmQueryResult("getHrmJobTitleInfo", false, List.of()));

        assertThat(service.departments("10").nullResult()).isTrue();
        assertThat(service.jobTitles("10", "20").nullResult()).isFalse();
        verify(client).getHrmDepartmentInfo("10");
        verify(client).getHrmJobTitleInfo("10", "20");
    }
}
