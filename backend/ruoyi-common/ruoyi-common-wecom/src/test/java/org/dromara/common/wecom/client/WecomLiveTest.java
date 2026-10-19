package org.dromara.common.wecom.client;

import me.chanjar.weixin.cp.api.WxCpService;
import me.chanjar.weixin.cp.bean.WxCpDepart;
import me.chanjar.weixin.cp.bean.WxCpUser;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import org.dromara.common.wecom.config.WecomAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式启用的真实只读探测；远端业务错误作为脱敏诊断结果输出，不等于接口成功。
 * 普通测试不会读取凭据或访问企业微信，不在报告中输出原始令牌和个人资料。
 */
@EnabledIfSystemProperty(named = "wecom.live", matches = "true")
class WecomLiveTest {

    @Test
    void probesAvailableReadOnlyEndpoints() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(WecomAutoConfiguration.class))
            .withPropertyValues("wecom.enabled=true", "wecom.corp-id=" + env("WECOM_CORP_ID", ""),
                "wecom.corp-secret=" + env("WECOM_CORP_SECRET", ""),
                "wecom.oauth-agent-id=" + env("WECOM_OAUTH_AGENT_ID", ""))
            .run(context -> {
                // 不把携带外部配置的 context 对象用于失败断言，避免诊断时打印凭据。
                assertThat(context.getStartupFailure() == null).as("企业微信自动配置必须正常启动").isTrue();
                configureTestProxy(context.getBean(WxCpService.class));
                WecomClient client = context.getBean(WecomClient.class);
                String token = probe("gettoken", client::getAccessToken, "取得凭证，值不输出");
                if (token == null) {
                    System.out.println("WECOM_PROBE directory/auth=BLOCKED prerequisite=gettoken");
                } else {
                    probeDirectory(client);
                    String code = env("WECOM_OAUTH_CODE", "");
                    if (code.isBlank()) {
                        System.out.println("WECOM_PROBE auth/getuserinfo=NOT_RUN missing=WECOM_OAUTH_CODE");
                    } else {
                        probe("auth/getuserinfo", () -> client.getAuthUserInfo(code), "已解析身份，值不输出");
                    }
                }
                String redirectUri = env("WECOM_OAUTH_REDIRECT_URI", "");
                if (redirectUri.isBlank()) {
                    System.out.println("WECOM_PROBE oauth2/authorize=NOT_RUN missing=WECOM_OAUTH_REDIRECT_URI");
                } else {
                    String url = client.buildAuthorizationUrl(redirectUri, "WecomProbeState123");
                    assertThat(url.startsWith("https://open.weixin.qq.com/connect/oauth2/authorize?")).isTrue();
                    System.out.println("WECOM_PROBE oauth2/authorize=BUILT_ONLY browserAuthorization=NOT_RUN");
                }
            });
    }

    private void configureTestProxy(WxCpService sdk) {
        String proxyHost = env("WECOM_TEST_PROXY_HOST", "");
        if (!proxyHost.isBlank()) {
            // 仅在显式联调中复用当前机器的出站代理，不改变生产配置或关闭 TLS 校验。
            WxCpDefaultConfigImpl storage = (WxCpDefaultConfigImpl) sdk.getWxCpConfigStorage();
            storage.setHttpProxyHost(proxyHost);
            storage.setHttpProxyPort(Integer.parseInt(env("WECOM_TEST_PROXY_PORT", "8080")));
            sdk.initHttp();
        }
    }

    private void probeDirectory(WecomClient client) {
        List<WxCpDepart> departments = probe("department/simplelist", () -> client.listDepartmentIds(null), "已解析 ID 列表");
        String departmentEnv = env("WECOM_TEST_DEPARTMENT_ID", "");
        long departmentId = departmentEnv.isBlank()
            ? (departments == null || departments.isEmpty() ? 1L : departments.getFirst().getId())
            : Long.parseLong(departmentEnv);
        probe("department/get", () -> client.getDepartment(departmentId), "已解析部门详情，值不输出");
        // 只读一个部门且不递归，以取得一个有效 UserId 后验证 user/get，不拉取全量通讯录。
        List<WxCpUser> users = probe("user/list", () -> client.listUsers(departmentId, false), "已解析成员列表");
        String userId = env("WECOM_TEST_USER_ID", "");
        if (userId.isBlank() && users != null && !users.isEmpty()) {
            userId = users.getFirst().getUserId();
        }
        if (userId.isBlank()) {
            System.out.println("WECOM_PROBE user/get=NOT_RUN missing=visibleUserId");
        } else {
            String selectedUserId = userId;
            probe("user/get", () -> client.getUser(selectedUserId), "已解析成员详情，值不输出");
        }
    }

    private <T> T probe(String endpoint, Supplier<T> action, String success) {
        try {
            T result = action.get();
            if (result == null || (result instanceof String text && text.isBlank())) {
                throw new AssertionError("SDK 返回空结果：" + endpoint);
            }
            String count = result instanceof List<?> list ? " count=" + list.size() : "";
            System.out.println("WECOM_PROBE " + endpoint + "=OK " + success + count);
            return result;
        } catch (WecomClientException error) {
            System.out.println("WECOM_PROBE " + endpoint + "=REMOTE_ERROR errorCode=" + error.getErrorCode()
                + " failureType=" + error.getFailureType());
            return null;
        }
    }

    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
