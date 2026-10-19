package org.dromara.common.wecom.client;

import com.sun.net.httpserver.HttpServer;
import me.chanjar.weixin.common.error.WxRuntimeException;
import me.chanjar.weixin.cp.api.WxCpService;
import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 SDK 请求本地 HTTP 服务，验证路径、参数和响应解析。 */
class WecomClientTest {

    private HttpServer server;
    private final List<URI> requests = new ArrayList<>();
    private WxCpDefaultConfigImpl storage;
    private WecomClient client;
    private boolean rejectTokenOnce;
    private String tokenResponse = "{\"errcode\":0,\"access_token\":\"test-token\",\"expires_in\":7200}";
    private String userResponse = "{\"errcode\":0,\"userid\":\"member-1\",\"name\":\"测试成员\"}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            requests.add(uri);
            String path = uri.getPath();
            String response = switch (path) {
                case "/cgi-bin/gettoken" -> tokenResponse;
                case "/cgi-bin/auth/getuserinfo" -> "{\"errcode\":0,\"userid\":\"member-1\"}";
                case "/cgi-bin/user/get" -> userResponse;
                case "/cgi-bin/user/list" -> "{\"errcode\":0,\"userlist\":[{\"userid\":\"member-1\",\"name\":\"测试成员\"}]}";
                case "/cgi-bin/department/simplelist" -> "{\"errcode\":0,\"department_id\":[{\"id\":2,\"parentid\":1,\"order\":10}]}";
                case "/cgi-bin/department/get" -> "{\"errcode\":0,\"department\":{\"id\":2,\"name\":\"测试部门\",\"parentid\":1}}";
                default -> "{\"errcode\":40000,\"errmsg\":\"unexpected path\"}";
            };
            if (rejectTokenOnce && path.equals("/cgi-bin/user/get")) {
                rejectTokenOnce = false;
                response = "{\"errcode\":42001,\"errmsg\":\"token expired\"}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        storage = new WxCpDefaultConfigImpl();
        storage.setCorpId("test-corp");
        storage.setCorpSecret("test-secret");
        storage.setAgentId(42);
        storage.setBaseApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WxCpServiceImpl sdk = new WxCpServiceImpl();
        sdk.setWxCpConfigStorage(storage);
        client = new WecomClient(sdk);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getsAndReusesTokenForSubsequentSdkCalls() {
        assertThat(client.getAccessToken()).isEqualTo("test-token");
        assertThat(client.getAccessToken()).isEqualTo("test-token");
        assertThat(client.getUser("member-1").getUserId()).isEqualTo("member-1");
        assertThat(requests.stream().filter(uri -> uri.getPath().endsWith("/gettoken"))).hasSize(1);
        assertThat(query(requests.getFirst())).containsEntry("corpid", "test-corp")
            .containsEntry("corpsecret", "test-secret");
        assertThat(query(lastRequest())).containsEntry("access_token", "test-token");
    }

    @Test
    void usesAuthUserInfoInsteadOfLegacyEndpointAndEncodesCode() {
        assertThat(client.getAuthUserInfo("code+with&special=chars").getUserId()).isEqualTo("member-1");
        assertThat(lastRequest().getPath()).isEqualTo("/cgi-bin/auth/getuserinfo");
        assertThat(query(lastRequest())).containsEntry("code", "code+with&special=chars");
    }

    @Test
    void readsMemberWithoutAllowingQueryParameterInjection() {
        assertThat(client.getUser("member+id&fetch_child=1").getName()).isEqualTo("测试成员");
        assertThat(query(lastRequest())).containsEntry("userid", "member+id&fetch_child=1")
            .doesNotContainKey("fetch_child");
    }

    @Test
    void listsDepartmentMembersWithExplicitRecursionFlag() {
        assertThat(client.listUsers(2L, false)).hasSize(1);
        assertThat(lastRequest().getPath()).isEqualTo("/cgi-bin/user/list");
        assertThat(query(lastRequest())).containsEntry("department_id", "2").containsEntry("fetch_child", "0");
        client.listUsers(2L, true);
        assertThat(query(lastRequest())).containsEntry("fetch_child", "1");
    }

    @Test
    void parsesDepartmentIdListAndSingleDepartment() {
        var ids = client.listDepartmentIds(1L);
        assertThat(ids).hasSize(1);
        assertThat(ids.getFirst().getId()).isEqualTo(2L);
        assertThat(lastRequest().getPath()).isEqualTo("/cgi-bin/department/simplelist");
        assertThat(query(lastRequest())).containsEntry("id", "1");
        client.listDepartmentIds(null);
        assertThat(query(lastRequest())).doesNotContainKey("id");
        assertThat(client.getDepartment(2L).getName()).isEqualTo("测试部门");
        assertThat(lastRequest().getPath()).isEqualTo("/cgi-bin/department/get");
        assertThat(query(lastRequest())).containsEntry("id", "2");
    }

    @Test
    void refreshesExpiredTokenAndRetriesReadOnce() {
        rejectTokenOnce = true;
        assertThat(client.getUser("member-1").getUserId()).isEqualTo("member-1");
        assertThat(requests.stream().filter(uri -> uri.getPath().endsWith("/gettoken"))).hasSize(2);
        assertThat(requests.stream().filter(uri -> uri.getPath().endsWith("/user/get"))).hasSize(2);
    }

    @Test
    void preservesOnlyOperationAndErrorCodeOnUpstreamFailure() {
        userResponse = "{\"errcode\":60011,\"errmsg\":\"private-user test-token test-secret\"}";
        assertThatThrownBy(() -> client.getUser("member-1"))
            .isInstanceOfSatisfying(WecomClientException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(60011);
                assertThat(error.getMessage()).contains("60011", "读取成员")
                    .doesNotContain("private-user", "test-token", "test-secret");
                assertThat(error.getCause()).isNull();
            });
    }

    @Test
    void preservesSafeNetworkFailureTypeWithoutSensitiveCause() throws Exception {
        WxCpService sdk = mock(WxCpService.class);
        when(sdk.getAccessToken()).thenThrow(new WxRuntimeException(new SocketTimeoutException("private-token-url")));
        assertThatThrownBy(() -> new WecomClient(sdk).getAccessToken())
            .isInstanceOfSatisfying(WecomClientException.class, error -> {
                assertThat(error.getErrorCode()).isNull();
                assertThat(error.getFailureType()).isEqualTo("SocketTimeoutException");
                assertThat(error.getMessage()).doesNotContain("private-token-url");
                assertThat(error.getCause()).isNull();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"private-response-token\"",
        "{\"errcode\":0,\"userid\":\"member-1\",\"department\":[\"private-member\"]}"})
    void sanitizesUnexpectedSdkJsonParsingFailures(String response) {
        userResponse = response;
        assertThatThrownBy(() -> client.getUser("member-1"))
            .isInstanceOfSatisfying(WecomClientException.class, error -> {
                assertThat(error.getErrorCode()).isNull();
                assertThat(error.getFailureType()).isNotBlank();
                assertThat(error.getMessage()).doesNotContain("private-response-token", "private-member");
                assertThat(error.getCause()).isNull();
            });
    }

    @Test
    void sanitizesUnexpectedTokenResponse() {
        tokenResponse = "\"private-response-token\"";
        assertThatThrownBy(client::getAccessToken)
            .isInstanceOfSatisfying(WecomClientException.class, error -> {
                assertThat(error.getMessage()).doesNotContain("private-response-token");
                assertThat(error.getCause()).isNull();
            });
    }

    @Test
    void buildsEncodedAuthorizationUrlWithAgentIdAndStateWithoutNetworkCall() {
        String callback = "https://example.com/wecom/callback?next=/首页&from=wecom";
        URI url = URI.create(client.buildAuthorizationUrl(callback, "SessionNonce123"));
        assertThat(url.getHost()).isEqualTo("open.weixin.qq.com");
        assertThat(url.getPath()).isEqualTo("/connect/oauth2/authorize");
        assertThat(url.getFragment()).isEqualTo("wechat_redirect");
        assertThat(query(url)).containsEntry("appid", "test-corp").containsEntry("agentid", "42")
            .containsEntry("redirect_uri", callback).containsEntry("state", "SessionNonce123")
            .containsEntry("scope", "snsapi_base").containsEntry("response_type", "code");
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsInvalidInputBeforeNetworkCall() {
        assertThatThrownBy(() -> client.getUser(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getAuthUserInfo("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getDepartment(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.listDepartmentIds(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.listUsers(-1L, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.buildAuthorizationUrl("javascript:alert(1)", "State1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.buildAuthorizationUrl("https://example.com/cb", "bad&state"))
            .isInstanceOf(IllegalArgumentException.class);
        storage.setAgentId(null);
        assertThatThrownBy(() -> client.buildAuthorizationUrl("https://example.com/cb", "State1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    private URI lastRequest() {
        return requests.getLast();
    }

    private Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
            .filter(part -> !part.isEmpty())
            .map(part -> part.split("=", 2))
            .collect(Collectors.toMap(parts -> decode(parts[0]), parts -> decode(parts[1])));
    }

    private String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }
}
