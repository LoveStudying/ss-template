package org.dromara.common.social.request;

import cn.hutool.core.util.StrUtil;
import me.zhyd.oauth.cache.AuthStateCache;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.config.AuthSource;
import me.zhyd.oauth.enums.AuthUserGender;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthDefaultRequest;
import me.zhyd.oauth.utils.UrlBuilder;
import me.zhyd.oauth.utils.JsonObject;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 三生统一认证授权码适配，使用 AD 账号标识第三方身份，不同步公司角色到本地权限。
 */
public class ThreeSBioAuthRequest extends AuthDefaultRequest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /**
     * 创建三生 SSO 请求，沿用项目的授权状态缓存。
     *
     * @param config 客户端凭据、SSO 服务地址、回调地址及授权范围
     * @param stateCache 授权状态缓存
     */
    public ThreeSBioAuthRequest(AuthConfig config, AuthStateCache stateCache) {
        super(config, new SsoSource(config.getServerUrl()), stateCache);
    }

    @Override
    protected void checkConfig(AuthConfig config) {
        if (StrUtil.hasBlank(config.getClientId(), config.getClientSecret(), config.getRedirectUri(), config.getServerUrl())
            || config.getScopes() == null || config.getScopes().isEmpty()
            || config.getScopes().stream().anyMatch(StrUtil::isBlank)) {
            throw new AuthException("三生 SSO 配置未完成，请填写客户端 ID、密钥、回调地址和授权范围");
        }
        validateUrl(config.getServerUrl());
        validateUrl(config.getRedirectUri());
        super.checkConfig(config);
    }

    /**
     * 生成三生登录地址，携带授权范围并缓存 state 以校验回调。
     *
     * @param state 本次授权的随机状态
     * @return 浏览器授权地址
     */
    @Override
    public String authorize(String state) {
        return UrlBuilder.fromBaseUrl(source.authorize())
            .queryParam("response_type", "code")
            .queryParam("client_id", config.getClientId())
            .queryParam("redirect_uri", config.getRedirectUri())
            .queryParam("scope", String.join(" ", config.getScopes()))
            .queryParam("state", getRealState(state))
            .build();
    }

    /**
     * 使用表单请求将授权码换取公司 SSO 令牌。
     *
     * @param callback 授权回调
     * @return 公司 SSO 令牌，仅供服务端读取身份
     */
    @Override
    public AuthToken getAccessToken(AuthCallback callback) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", config.getClientId());
        form.put("client_secret", config.getClientSecret());
        form.put("code", callback.getCode());
        form.put("redirect_uri", config.getRedirectUri());
        form.put("scope", String.join(" ", config.getScopes()));
        String body = form.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        JsonObject response = execute(HttpRequest.newBuilder(URI.create(source.accessToken()))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
        if (!(response.get("access_token") instanceof String accessToken)
            || !accessToken.matches("[\\x21-\\x7E]+")) {
            throw new AuthException("三生 SSO 换取令牌失败");
        }
        return AuthToken.builder()
            .accessToken(accessToken)
            .refreshToken(response.getString("refresh_token"))
            .tokenType(response.getString("token_type"))
            .expireIn(response.get("expires_in") instanceof Number expiresIn ? expiresIn.intValue() : 0)
            .scope(String.join(" ", config.getScopes()))
            .build();
    }

    /**
     * 读取公司用户身份，拒绝冻结账号及缺少 AD 唯一标识的响应。
     *
     * @param token 公司 SSO 访问令牌
     * @return 供本地绑定及登录使用的第三方身份
     */
    @Override
    public AuthUser getUserInfo(AuthToken token) {
        JsonObject response = execute(HttpRequest.newBuilder(URI.create(source.userInfo()))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token.getAccessToken())
            .GET().build());
        if (!Objects.equals(response.get("code"), 200) || !(response.get("data") instanceof JsonObject user)) {
            throw new AuthException("三生 SSO 获取用户信息失败");
        }
        if (!Objects.equals(user.get("status"), 1)) {
            throw new AuthException("三生 SSO 账号不可用");
        }
        if (!(user.get("adAccount") instanceof String adAccount) || StrUtil.isBlank(adAccount)) {
            throw new AuthException("三生 SSO 未返回有效的 AD 账号");
        }
        return AuthUser.builder()
            .uuid(adAccount)
            .source(source.getName())
            .username(user.getString("username"))
            .nickname(user.getString("nickname"))
            .avatar(user.getString("avatar"))
            .email(user.getString("email"))
            .gender(Objects.equals(user.get("gender"), 1) ? AuthUserGender.MALE
                : Objects.equals(user.get("gender"), 2) ? AuthUserGender.FEMALE : AuthUserGender.UNKNOWN)
            .token(token)
            .build();
    }

    private static JsonObject execute(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 不透传 SSO 原始错误体，避免密钥、令牌或个人信息进入响应和日志。
            if (response.statusCode() != 200) {
                throw new AuthException("三生 SSO 请求失败，请联系管理员检查配置");
            }
            return JsonObject.parseObject(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AuthException("三生 SSO 请求被中断");
        } catch (IOException exception) {
            throw new AuthException("三生 SSO 连接失败，请稍后重试");
        } catch (JacksonException exception) {
            throw new AuthException("三生 SSO 响应格式不正确");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getUserInfo() == null && uri.getFragment() == null) {
                return;
            }
        } catch (IllegalArgumentException exception) {
            throw new AuthException("三生 SSO 服务地址或回调地址格式不正确");
        }
        throw new AuthException("三生 SSO 服务地址或回调地址格式不正确");
    }

    private record SsoSource(String serverUrl) implements AuthSource {

        /** 返回三生浏览器登录地址。 */
        @Override
        public String authorize() {
            return endpoint("/user/login");
        }

        /** 返回三生授权码换令牌地址。 */
        @Override
        public String accessToken() {
            return endpoint("/sss-sso/oauth2/token");
        }

        /** 返回三生用户信息地址。 */
        @Override
        public String userInfo() {
            return endpoint("/sss-sso/oauth2/user/info");
        }

        /** 返回与配置、回调及绑定表一致的平台标识。 */
        @Override
        public String getName() {
            return "sso";
        }

        /** 返回本平台的授权请求实现。 */
        @Override
        public Class<? extends AuthDefaultRequest> getTargetClass() {
            return ThreeSBioAuthRequest.class;
        }

        private String endpoint(String path) {
            return StrUtil.removeSuffix(serverUrl, "/") + path;
        }
    }
}
