package org.dromara.common.hrm.client;

import org.dromara.common.hrm.config.HrmProperties;
import org.dromara.common.hrm.domain.HrmQueryResult;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** 需要显式指定 -Dhrm.live=true 的只读联调；普通测试不会访问真实 OA。 */
@EnabledIfSystemProperty(named = "hrm.live", matches = "true")
class HrmLiveTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"getHrmSubcompanyInfo", "getHrmDepartmentInfo", "getHrmJobTitleInfo", "getHrmUserInfo"})
    void queriesLiveService(String method) {
        HrmProperties properties = new HrmProperties();
        properties.setEndpoint(env("HRM_ENDPOINT", "http://139.224.241.191:8080/services/HrmService"));
        properties.setClientIp(env("HRM_CLIENT_IP", ""));
        HrmUserQuery query = new HrmUserQuery();
        // 默认查询不存在的测试编码，防止首次联调意外拉取全量人员资料。
        query.setWorkcode(env("HRM_TEST_WORKCODE", "__HRM_CONNECTIVITY_PROBE__"));
        String subcompanyId = env("HRM_TEST_SUBCOMPANY_ID", "");
        String departmentId = env("HRM_TEST_DEPARTMENT_ID", "");
        query.setSubcompanyId(subcompanyId);
        query.setDepartmentId(departmentId);
        try (HrmClient client = new HrmClient(properties)) {
            HrmQueryResult result = switch (method) {
                case "getHrmSubcompanyInfo" -> client.getHrmSubcompanyInfo();
                case "getHrmDepartmentInfo" -> client.getHrmDepartmentInfo(subcompanyId);
                case "getHrmJobTitleInfo" -> client.getHrmJobTitleInfo(subcompanyId, departmentId);
                case "getHrmUserInfo" -> client.getHrmUserInfo(query);
                default -> throw new IllegalArgumentException("非只读查询方法");
            };
            System.out.println(result);
            result.records().stream().filter(Objects::nonNull).findFirst()
                .ifPresent(record -> System.out.println("返回字段=" + record.fields().keySet()));
            assertThat(result.nullResult())
                .as("%s 返回 xsi:nil，尚未获取数据；请接口方确认客户端 IP 白名单和 OA 日志", method)
                .isFalse();
        }
    }

    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
