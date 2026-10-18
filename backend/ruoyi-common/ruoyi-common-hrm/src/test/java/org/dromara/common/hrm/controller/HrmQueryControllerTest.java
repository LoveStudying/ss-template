package org.dromara.common.hrm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.hrm.client.HrmClientException;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.dromara.common.hrm.domain.vo.HrmQueryVo;
import org.dromara.common.hrm.service.HrmQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class HrmQueryControllerTest {

    private final HrmQueryService service = mock(HrmQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HrmQueryController(service)).build();

    @Test
    void exposesAllFourGetRoutesAndBindsFilters() throws Exception {
        HrmQueryVo empty = new HrmQueryVo("test", false, List.of());
        when(service.subcompanies()).thenReturn(empty);
        when(service.departments("10")).thenReturn(empty);
        when(service.jobTitles("10", "20")).thenReturn(empty);
        when(service.users(any(HrmUserQuery.class))).thenReturn(empty);

        mvc.perform(get("/hrm/subcompanies")).andExpect(jsonPath("$.data.nullResult").value(false));
        mvc.perform(get("/hrm/departments").param("subcompanyId", "10"))
            .andExpect(jsonPath("$.data.records").isArray());
        mvc.perform(get("/hrm/job-titles").param("subcompanyId", "10").param("departmentId", "20"))
            .andExpect(jsonPath("$.data.method").value("test"));
        mvc.perform(get("/hrm/users").param("workcode", "EMP001").param("lastChangeDate", "2026-10-09"))
            .andExpect(jsonPath("$.code").value(200));

        verify(service).departments("10");
        verify(service).jobTitles("10", "20");
        var query = forClass(HrmUserQuery.class);
        verify(service).users(query.capture());
        assertThat(query.getValue().getWorkcode()).isEqualTo("EMP001");
        assertThat(query.getValue().getLastChangeDate()).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    void returnsSanitizedClientError() throws Exception {
        when(service.subcompanies()).thenThrow(new HrmClientException("调用 OA 超时"));

        mvc.perform(get("/hrm/subcompanies"))
            .andExpect(jsonPath("$.msg").value("调用 OA 超时"));
    }

    @Test
    void eachQueryRequiresItsOwnPermission() throws Exception {
        assertPermission("subcompanies", "hrm:subcompany:query");
        assertPermission("departments", "hrm:department:query", String.class);
        assertPermission("jobTitles", "hrm:jobtitle:query", String.class, String.class);
        assertPermission("users", "hrm:user:query", HrmUserQuery.class);
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes) throws Exception {
        Method method = HrmQueryController.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(SaCheckPermission.class).value()).containsExactly(permission);
    }
}
