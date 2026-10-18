package org.dromara.common.social;

import me.zhyd.oauth.cache.AuthStateCache;
import org.dromara.common.social.config.SocialAutoConfiguration;
import org.dromara.common.social.config.properties.SocialLoginConfigProperties;
import org.dromara.common.social.config.properties.SocialProperties;
import org.dromara.common.social.utils.AuthRedisStateCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("common-social 功能单元测试")
class SocialConfigurationTest {

    /**
     * 验证社交登录自动配置提供项目约定的 Redis 授权状态缓存实现。
     */
    @Test
    @DisplayName("创建授权状态缓存")
    void shouldCreateRedisAuthStateCache() {
        AuthStateCache cache = new SocialAutoConfiguration().authStateCache();

        assertInstanceOf(AuthRedisStateCache.class, cache);
    }

    /**
     * 验证三生 SSO 的客户端凭据和授权范围可以按类型完整保存。
     */
    @Test
    @DisplayName("保存社交登录配置")
    void shouldStoreSocialProviderConfiguration() {
        SocialLoginConfigProperties sso = new SocialLoginConfigProperties();
        sso.setClientId("client-id");
        sso.setClientSecret("client-secret");
        sso.setScopes(List.of("read"));
        SocialProperties properties = new SocialProperties();
        properties.setType(Map.of("sso", sso));

        assertEquals("client-id", properties.getType().get("sso").getClientId());
        assertEquals(List.of("read"), properties.getType().get("sso").getScopes());
    }
}
