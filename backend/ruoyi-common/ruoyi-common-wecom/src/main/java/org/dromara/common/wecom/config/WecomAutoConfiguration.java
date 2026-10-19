package org.dromara.common.wecom.config;

import me.chanjar.weixin.cp.api.WxCpService;
import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import org.dromara.common.wecom.client.WecomClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.Assert;

/** 企业微信核心 SDK 自动装配；关闭时不要求凭据，创建 Bean 时不调用远端。 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "wecom", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WecomProperties.class)
public class WecomAutoConfiguration {

    /**
     * 注册单应用 SDK，令牌按 SDK 默认实现缓存在当前 JVM 内。
     *
     * @param properties 企业微信应用配置
     * @return 企业微信 SDK 服务
     */
    @Bean
    @ConditionalOnMissingBean(WxCpService.class)
    public WxCpService wecomService(WecomProperties properties) {
        Assert.hasText(properties.getCorpId(), "启用企业微信时必须配置 wecom.corp-id");
        Assert.hasText(properties.getCorpSecret(), "启用企业微信时必须配置 wecom.corp-secret");
        Assert.isTrue(properties.getOauthAgentId() == null || properties.getOauthAgentId() > 0,
            "wecom.oauth-agent-id 必须为正数");
        WxCpDefaultConfigImpl storage = new WxCpDefaultConfigImpl();
        storage.setCorpId(properties.getCorpId());
        storage.setCorpSecret(properties.getCorpSecret());
        storage.setAgentId(properties.getOauthAgentId());
        WxCpServiceImpl sdk = new WxCpServiceImpl();
        sdk.setWxCpConfigStorage(storage);
        return sdk;
    }

    /**
     * 注册只读客户端，供业务模块注入调用。
     *
     * @param service 企业微信 SDK 服务
     * @return 企业微信只读客户端
     */
    @Bean
    @ConditionalOnMissingBean(WecomClient.class)
    public WecomClient wecomClient(WxCpService service) {
        return new WecomClient(service);
    }
}
