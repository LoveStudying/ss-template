package org.dromara.system.domain.vo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("第三方绑定列表的敏感字段回归测试")
class SysSocialVoSerializationTest {

    @Test
    @DisplayName("展示绑定身份但不返回第三方凭据")
    void shouldHideCredentialsFromBindingList() {
        SysSocialVo binding = new SysSocialVo();
        binding.setId(1L);
        binding.setSource("sso");
        binding.setUserName("test-account");
        binding.setAccessToken("test-access-token");
        binding.setRefreshToken("test-refresh-token");
        binding.setAccessCode("test-access-code");
        binding.setIdToken("test-id-token");
        binding.setMacKey("test-mac-key");
        binding.setCode("test-code");
        binding.setOauthToken("test-oauth-token");
        binding.setOauthTokenSecret("test-oauth-secret");
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(List.of(binding))).get(0);

        assertEquals("sso", json.get("source").asString());
        assertEquals("test-account", json.get("userName").asString());
        for (String field : List.of("accessToken", "refreshToken", "accessCode", "idToken", "macKey", "code", "oauthToken", "oauthTokenSecret")) {
            assertFalse(json.has(field), "绑定列表不应返回 " + field);
        }
    }
}
