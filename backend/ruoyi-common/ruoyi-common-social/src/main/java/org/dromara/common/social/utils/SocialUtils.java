package org.dromara.common.social.utils;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.social.config.properties.SocialLoginConfigProperties;
import org.dromara.common.social.config.properties.SocialProperties;
import org.dromara.common.social.request.ThreeSBioAuthRequest;

/**
 * 认证授权工具类
 *
 * @author thiszhc
 */
public class SocialUtils {

    /**
     * 执行第三方登录授权回调。
     *
     * @param source           社交平台类型
     * @param code             授权码
     * @param state            状态值
     * @param socialProperties 社交平台配置
     * @return 授权响应
     * @throws AuthException 授权异常
     */
    public static AuthResponse<AuthUser> loginAuth(String source, String code, String state, SocialProperties socialProperties) throws AuthException {
        AuthRequest authRequest = getAuthRequest(source, socialProperties);
        AuthCallback callback = new AuthCallback();
        callback.setCode(code);
        callback.setState(state);
        return authRequest.login(callback);
    }

    /**
     * 根据平台类型构建授权请求实例。
     *
     * @param source           社交平台类型
     * @param socialProperties 社交平台配置
     * @return 授权请求
     * @throws AuthException 授权异常
     */
    public static AuthRequest getAuthRequest(String source, SocialProperties socialProperties) throws AuthException {
        SocialLoginConfigProperties obj = socialProperties.getType() == null ? null : socialProperties.getType().get(source);
        if (!"sso".equals(source) || ObjectUtil.isNull(obj)) {
            throw new AuthException("不支持的第三方登录类型");
        }
        if (StrUtil.hasBlank(obj.getClientId(), obj.getClientSecret(), obj.getRedirectUri(), obj.getServerUrl())
            || obj.getScopes() == null || obj.getScopes().isEmpty()) {
            throw new AuthException("三生 SSO 配置未完成，请填写客户端 ID、密钥、回调地址和授权范围");
        }
        AuthConfig.AuthConfigBuilder builder = AuthConfig.builder()
            .clientId(obj.getClientId())
            .clientSecret(obj.getClientSecret())
            .redirectUri(obj.getRedirectUri())
            .serverUrl(obj.getServerUrl())
            .scopes(obj.getScopes());
        return new ThreeSBioAuthRequest(builder.build(), SpringUtils.getBean(AuthRedisStateCache.class));
    }
}
