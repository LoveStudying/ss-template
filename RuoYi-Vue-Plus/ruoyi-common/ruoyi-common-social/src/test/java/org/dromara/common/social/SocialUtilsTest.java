package org.dromara.common.social;

import cn.hutool.extra.spring.SpringUtil;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.request.AuthRequest;
import org.dromara.common.social.config.properties.SocialLoginConfigProperties;
import org.dromara.common.social.config.properties.SocialProperties;
import org.dromara.common.social.utils.AuthRedisStateCache;
import org.dromara.common.social.utils.SocialUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("SocialUtils 三生 SSO 配置契约测试")
class SocialUtilsTest {

    @BeforeAll
    static void initializeSocialContext() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("authRedisStateCache", mock(AuthRedisStateCache.class));
        context.refresh();
        new SpringUtil().setApplicationContext(context);
    }

    @Test
    @DisplayName("三生配置映射到正确的授权服务及范围")
    void shouldBuildSsoRequestWithBaseConfig() {
        AuthRequest request = SocialUtils.getAuthRequest("sso", properties("sso", config()));
        URI authorizeUrl = URI.create(request.authorize("test-state"));

        assertEquals("login.example.test", authorizeUrl.getHost());
        assertEquals("/user/login", authorizeUrl.getPath());
        assertTrue(authorizeUrl.getRawQuery().contains("scope=read"));
    }

    @Test
    @DisplayName("即使配置了已移除的平台也不能授权")
    void shouldRejectRemovedProviders() {
        for (String source : List.of("github", "gitee", "wechat", "maxkey", "topiam")) {
            assertThrows(AuthException.class, () -> SocialUtils.getAuthRequest(source, properties(source, config())));
        }
    }

    @Test
    @DisplayName("缺少三生配置或配置未填写时拒绝授权")
    void shouldRejectMissingAndIncompleteConfiguration() {
        SocialProperties empty = new SocialProperties();
        assertThrows(AuthException.class, () -> SocialUtils.getAuthRequest("sso", empty));
        empty.setType(Map.of());
        assertThrows(AuthException.class, () -> SocialUtils.getAuthRequest("sso", empty));
        SocialLoginConfigProperties incomplete = config();
        incomplete.setClientId("");
        assertThrows(AuthException.class, () -> SocialUtils.getAuthRequest("sso", properties("sso", incomplete)));
        incomplete.setClientId("client-id");
        incomplete.setScopes(List.of());
        assertThrows(AuthException.class, () -> SocialUtils.getAuthRequest("sso", properties("sso", incomplete)));
    }

    private static SocialLoginConfigProperties config() {
        SocialLoginConfigProperties config = new SocialLoginConfigProperties();
        config.setClientId("client-id");
        config.setClientSecret("client-secret");
        config.setRedirectUri("https://app.example.test/social-callback?source=sso");
        config.setServerUrl("https://login.example.test");
        config.setScopes(List.of("read"));
        return config;
    }

    private static SocialProperties properties(String source, SocialLoginConfigProperties config) {
        SocialProperties properties = new SocialProperties();
        properties.setType(Map.of(source, config));
        return properties;
    }
}
