package org.dromara.common.wecom.client;

import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.common.error.WxRuntimeException;
import me.chanjar.weixin.common.util.http.URIUtil;
import me.chanjar.weixin.cp.api.WxCpService;
import me.chanjar.weixin.cp.bean.WxCpDepart;
import me.chanjar.weixin.cp.bean.WxCpOauth2UserInfo;
import me.chanjar.weixin.cp.bean.WxCpUser;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.List;

/** 企业微信自建应用只读客户端；返回对象可能含个人信息，调用方不得整体记录或直接公开。 */
public class WecomClient {

    private final WxCpService service;

    /**
     * 使用已配置应用凭据的核心 SDK 创建客户端。
     *
     * @param service 企业微信 SDK 服务
     */
    public WecomClient(WxCpService service) {
        Assert.notNull(service, "企业微信 SDK 服务不能为空");
        this.service = service;
    }

    /**
     * 获取访问凭证，由 SDK 缓存并按需刷新；禁止向前端返回或记录令牌。
     *
     * @return 应用访问凭证
     */
    public String getAccessToken() {
        return call("获取访问凭证", service::getAccessToken);
    }

    /**
     * 通过一次性网页授权 code 获取访问用户身份，使用 auth/getuserinfo 路径。
     *
     * @param code 企业微信回调携带的临时授权码，不能重复使用
     * @return 成员身份或非成员的 OpenId；业务层自行判断是否允许登录
     */
    public WxCpOauth2UserInfo getAuthUserInfo(String code) {
        Assert.hasText(code, "网页授权 code 不能为空");
        return call("获取访问用户身份", () -> service.getOauth2Service().getAuthUserInfo(encode(code)));
    }

    /**
     * 读取应用可见范围内的成员资料。
     *
     * @param userId 企业微信成员 UserId
     * @return SDK 成员资料
     */
    public WxCpUser getUser(String userId) {
        Assert.hasText(userId, "企业微信成员 UserId 不能为空");
        return call("读取成员", () -> service.getUserService().getById(encode(userId)));
    }

    /**
     * 获取指定部门的成员详情，不执行本地用户写入或同步。
     *
     * @param departmentId 正数部门 ID
     * @param fetchChild 是否同时获取子部门成员
     * @return 可见范围内的部门成员
     */
    public List<WxCpUser> listUsers(long departmentId, boolean fetchChild) {
        requireDepartmentId(departmentId);
        return call("获取部门成员详情", () -> service.getUserService().listByDepartment(departmentId, fetchChild, null));
    }

    /**
     * 获取子部门 ID 列表，响应只包含 ID、父部门 ID 和排序等简要信息。
     *
     * @param departmentId 正数部门 ID；null 表示查询应用可见范围内的部门列表
     * @return SDK 部门简要对象
     */
    public List<WxCpDepart> listDepartmentIds(Long departmentId) {
        if (departmentId != null) {
            requireDepartmentId(departmentId);
        }
        return call("获取子部门 ID 列表", () -> service.getDepartmentService().simpleList(departmentId));
    }

    /**
     * 获取单个部门详情。
     *
     * @param departmentId 正数部门 ID
     * @return SDK 部门资料
     */
    public WxCpDepart getDepartment(long departmentId) {
        requireDepartmentId(departmentId);
        return call("获取单个部门详情", () -> service.getDepartmentService().get(departmentId));
    }

    /**
     * 构造 snsapi_base 网页授权链接，不发起网络请求，也不自动创建登录会话。
     *
     * @param redirectUri 已在企业微信配置可信域名的 HTTP(S) 回调地址
     * @param state 调用方生成并在回调校验的一次性随机串，限 1 至 128 位字母和数字
     * @return 包含企业 ID、AgentId 和回调地址的授权链接
     */
    public String buildAuthorizationUrl(String redirectUri, String state) {
        Assert.hasText(redirectUri, "网页授权回调地址不能为空");
        URI callback = URI.create(redirectUri);
        Assert.isTrue(("https".equalsIgnoreCase(callback.getScheme()) || "http".equalsIgnoreCase(callback.getScheme()))
            && callback.getHost() != null && callback.getUserInfo() == null && callback.getFragment() == null,
            "网页授权回调必须为不含用户信息和片段的 HTTP(S) 地址");
        Assert.isTrue(state != null && state.matches("[A-Za-z0-9]{1,128}"), "state 必须为 1 至 128 位字母或数字");
        Integer agentId = service.getWxCpConfigStorage().getAgentId();
        Assert.isTrue(agentId != null && agentId > 0, "构造授权链接前必须配置 wecom.oauth-agent-id");
        String url = service.getOauth2Service().buildAuthorizationUrl(redirectUri, state, "snsapi_base");
        // SDK 在 snsapi_base 下省略 agentid；显式补齐，确保入口指向配置的自建应用。
        return url.replace("#wechat_redirect", "&agentid=" + agentId + "#wechat_redirect");
    }

    private void requireDepartmentId(long departmentId) {
        Assert.isTrue(departmentId > 0, "企业微信部门 ID 必须为正数");
    }

    private String encode(String value) {
        // SDK 将这两个参数直接拼入 URL，必须在边界编码，防止参数注入和加号失真。
        return URIUtil.encodeURIComponent(value);
    }

    private <T> T call(String operation, SdkCall<T> action) {
        try {
            return action.execute();
        } catch (WxErrorException error) {
            throw new WecomClientException(operation, error.getError() == null ? null : error.getError().getErrorCode());
        } catch (RuntimeException error) {
            // 第三方 SDK 的 JSON 解析异常也可能包含个人资料，只在该调用边界统一脱敏。
            Throwable cause = error instanceof WxRuntimeException && error.getCause() != null ? error.getCause() : error;
            throw new WecomClientException(operation, null, cause.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface SdkCall<T> {
        T execute() throws WxErrorException;
    }
}
