package org.dromara.common.social.config.properties;

import lombok.Data;

import java.util.List;

/**
 * 社交登录配置
 *
 * @author thiszhc
 */
@Data
public class SocialLoginConfigProperties {

    /**
     * 应用 ID
     */
    private String clientId;

    /**
     * 应用密钥
     */
    private String clientSecret;

    /**
     * 回调地址
     */
    private String redirectUri;

    /**
     * 自托管授权服务器地址
     */
    private String serverUrl;

    /**
     * 请求范围
     */
    private List<String> scopes;

}
