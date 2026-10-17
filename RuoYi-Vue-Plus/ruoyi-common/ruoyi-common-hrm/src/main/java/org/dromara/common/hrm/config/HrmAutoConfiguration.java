package org.dromara.common.hrm.config;

import org.dromara.common.hrm.client.HrmClient;
import org.dromara.common.hrm.controller.HrmQueryController;
import org.dromara.common.hrm.service.HrmQueryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 泛微人力资源只读客户端自动装配。
 * <p>
 * 仅在组织信息来源为 {@code hrm} 时装配，未配置来源时按默认的 {@code hrm} 处理；
 * {@code org-sync.source=none} 时不注册本模块的任何 Bean，也不要求任何 {@code hrm.*} 配置。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "org-sync", name = "source", havingValue = "hrm", matchIfMissing = true)
@EnableConfigurationProperties(HrmProperties.class)
@Import(HrmQueryController.class)
public class HrmAutoConfiguration {

    /**
     * 注册只读查询客户端，容器关闭时释放底层 HTTP 连接。
     *
     * @param properties 人力资源接口配置
     * @return 只读查询客户端
     */
    @Bean(destroyMethod = "close")
    public HrmClient hrmClient(HrmProperties properties) {
        return new HrmClient(properties);
    }

    /**
     * 注册查询服务，供联调控制器和同步适配实现使用。
     *
     * @param hrmClient 只读查询客户端
     * @return 查询服务
     */
    @Bean
    public HrmQueryService hrmQueryService(HrmClient hrmClient) {
        return new HrmQueryService(hrmClient);
    }
}
