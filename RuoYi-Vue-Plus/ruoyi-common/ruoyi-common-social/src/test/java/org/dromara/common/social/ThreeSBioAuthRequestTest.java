package org.dromara.common.social;

import cn.hutool.extra.spring.SpringUtil;
import com.sun.net.httpserver.HttpServer;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.dromara.common.social.config.properties.SocialLoginConfigProperties;
import org.dromara.common.social.config.properties.SocialProperties;
import org.dromara.common.social.utils.AuthRedisStateCache;
import org.dromara.common.social.utils.SocialUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("三生 SSO 授权码协议测试")
class ThreeSBioAuthRequestTest {

    private HttpServer server;
    private StaticApplicationContext context;
    private SocialLoginConfigProperties config;
    private final AtomicReference<String> tokenResponse = new AtomicReference<>(
        "{\"access_token\":\"test-access-token\",\"refresh_token\":\"test-refresh-token\",\"token_type\":\"Bearer\",\"expires_in\":86400}");
    private final AtomicReference<String> userResponse = new AtomicReference<>(
        "{\"code\":200,\"data\":{\"userId\":23,\"username\":\"login-name\",\"adAccount\":\"ad-account\",\"nickname\":\"测试用户\",\"status\":1,\"email\":\"test@example.test\"}}");
    private final AtomicReference<Map<String, String>> tokenForm = new AtomicReference<>();
    private final AtomicReference<String> tokenContentType = new AtomicReference<>();
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final AtomicInteger tokenStatus = new AtomicInteger(200);
    private final AtomicInteger userStatus = new AtomicInteger(200);
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger userCalls = new AtomicInteger();

    @BeforeEach
    void prepareProtocolServer() throws Exception {
        Map<String, String> states = new HashMap<>();
        AuthRedisStateCache cache = mock(AuthRedisStateCache.class);
        doAnswer(invocation -> {
            states.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(cache).cache(anyString(), anyString());
        when(cache.get(anyString())).thenAnswer(invocation -> states.get(invocation.getArgument(0)));
        when(cache.containsKey(anyString())).thenAnswer(invocation -> states.containsKey(invocation.getArgument(0)));
        context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("authRedisStateCache", cache);
        context.refresh();
        new SpringUtil().setApplicationContext(context);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sss-sso/oauth2/token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenForm.set(parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            tokenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = tokenResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(tokenStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/sss-sso/oauth2/user/info", exchange -> {
            userCalls.incrementAndGet();
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = userResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(userStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        config = new SocialLoginConfigProperties();
        config.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        config.setClientId("test-client");
        config.setClientSecret("test-secret");
        config.setRedirectUri("https://app.example.test/social-callback?source=sso");
        config.setScopes(List.of("read"));
    }

    @AfterEach
    void releaseResources() {
        server.stop(0);
        context.close();
    }

    @Test
    @DisplayName("授权地址包含协议参数和可校验的 state")
    void shouldBuildAuthorizationUrl() {
        URI url = URI.create(request().authorize("browser-state"));

        assertEquals("/user/login", url.getPath());
        assertEquals(Map.of("response_type", "code", "client_id", "test-client", "scope", "read",
            "redirect_uri", "https://app.example.test/social-callback?source=sso", "state", "browser-state"),
            parameters(url.getRawQuery()));
        assertFalse(url.toString().contains("test-secret"));
    }

    @Test
    @DisplayName("按文档换令牌并以 AD 账号建立稳定身份")
    void shouldExchangeCodeAndMapCompanyIdentity() {
        AuthResponse<AuthUser> response = login();

        assertTrue(response.ok(), response.getMsg());
        assertEquals(Map.of("grant_type", "authorization_code", "client_id", "test-client", "client_secret", "test-secret",
            "code", "authorization-code", "redirect_uri", "https://app.example.test/social-callback?source=sso", "scope", "read"), tokenForm.get());
        assertTrue(tokenContentType.get().startsWith("application/x-www-form-urlencoded"));
        assertEquals("Bearer test-access-token", authorizationHeader.get());
        AuthUser user = response.getData();
        assertEquals("sso", user.getSource());
        assertEquals("ad-account", user.getUuid());
        assertEquals("login-name", user.getUsername());
        assertEquals("测试用户", user.getNickname());
        assertEquals("test@example.test", user.getEmail());
        assertEquals(86400, user.getToken().getExpireIn());
        assertEquals("test-refresh-token", user.getToken().getRefreshToken());
    }

    @Test
    @DisplayName("缺少或伪造 state 时不调用 SSO")
    void shouldRejectInvalidStateBeforeHttpCalls() {
        AuthRequest request = request();
        request.authorize("browser-state");
        for (String state : List.of("", "forged-state")) {
            assertFalse(request.login(callback(state)).ok());
        }
        assertEquals(0, tokenCalls.get());
        assertEquals(0, userCalls.get());
    }

    @Test
    @DisplayName("令牌响应失败或缺少令牌时不读取用户")
    void shouldRejectFailedTokenExchange() {
        for (String body : List.of("{}", "{\"access_token\":\"\"}", "not-json")) {
            tokenResponse.set(body);
            assertFalse(login().ok());
        }
        tokenResponse.set("{\"access_token\":\"must-not-use\"}");
        tokenStatus.set(401);
        assertFalse(login().ok());
        assertEquals(0, userCalls.get());
    }

    @Test
    @DisplayName("拒绝冻结、缺少唯一标识及错误用户响应")
    void shouldRejectInvalidCompanyIdentity() {
        for (String body : List.of(
            "{\"code\":200,\"data\":{\"adAccount\":\"ad-account\",\"status\":0}}",
            "{\"code\":200,\"data\":{\"username\":\"login-name\",\"status\":1}}",
            "{\"code\":200,\"data\":{\"adAccount\":\"ad-account\"}}",
            "{\"code\":401,\"message\":\"test-access-token\"}", "{}", "not-json")) {
            userResponse.set(body);
            AuthResponse<AuthUser> response = login();
            assertFalse(response.ok());
            assertFalse(response.getMsg().contains("test-access-token"));
        }
        userResponse.set("{\"code\":200,\"data\":{\"adAccount\":\"ad-account\",\"status\":1}}");
        userStatus.set(500);
        assertFalse(login().ok());
    }

    @Test
    @DisplayName("缺少配置时给出清晰提示且不连接 SSO")
    void shouldRejectIncompleteConfiguration() {
        config.setClientSecret("");
        assertThrows(AuthException.class, this::request);
        assertEquals(0, tokenCalls.get());
    }

    private AuthResponse<AuthUser> login() {
        AuthRequest request = request();
        request.authorize("browser-state");
        return request.login(callback("browser-state"));
    }

    @Test
    @DisplayName("非法请求头令牌不能通过错误信息泄露")
    void shouldRejectMalformedTokenWithoutLeakingIt() {
        tokenResponse.set("{\"access_token\":\"sensitive-token\\r\\ninvalid-header\"}");

        AuthResponse<AuthUser> response = login();

        assertFalse(response.ok());
        assertFalse(response.getMsg().contains("sensitive-token"));
        assertEquals(0, userCalls.get());
    }

    private AuthRequest request() {
        SocialProperties properties = new SocialProperties();
        properties.setType(Map.of("sso", config));
        return SocialUtils.getAuthRequest("sso", properties);
    }

    private static AuthCallback callback(String state) {
        AuthCallback callback = new AuthCallback();
        callback.setCode("authorization-code");
        callback.setState(state);
        return callback;
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> values = new HashMap<>();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }
}
