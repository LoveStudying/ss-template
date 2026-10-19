package org.dromara.common.wecom.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 企业微信自建应用配置，凭据从配置文件提供，不参与对象字符串输出。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "wecom")
public class WecomProperties {

    /** 是否启用企业微信客户端，默认关闭。 */
    private boolean enabled;

    /** 企业 ID，对应管理后台的 CorpId。 */
    private String corpId;

    /** 自建应用密钥，禁止记录日志或返回给前端。 */
    private String corpSecret;

    /** 网页授权使用的自建应用 AgentId，构造授权链接时必须为正数。 */
    private Integer oauthAgentId;

    /** 网页授权回调完整地址，其域名须已在企业微信应用配置为可信域名。 */
    private String oauthRedirectUri;
}
