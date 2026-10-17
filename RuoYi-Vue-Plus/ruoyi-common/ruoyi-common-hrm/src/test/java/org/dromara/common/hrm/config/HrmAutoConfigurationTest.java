package org.dromara.common.hrm.config;

import org.dromara.common.hrm.client.HrmClient;
import org.dromara.common.hrm.controller.HrmQueryController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 HRM 客户端按组织信息来源条件装配，且未启用来源时不注册任何 HRM Bean。
 */
@DisplayName("HRM 自动配置条件加载测试")
class HrmAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HrmAutoConfiguration.class));

    /** 模拟启动类对 org.dromara 包的组件扫描，验证控制器自身的条件也能挡住扫描注册。 */
    @Configuration
    @ComponentScan("org.dromara.common.hrm.controller")
    static class ControllerScanConfiguration {
    }

    /**
     * 来源为 hrm 时注册客户端、查询服务和联调控制器。
     */
    @Test
    void registersClientAndControllerWhenSourceIsHrm() {
        runner.withPropertyValues("org-sync.source=hrm", "hrm.endpoint=http://127.0.0.1:1/services/HrmService")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(HrmClient.class);
                assertThat(ctx).hasSingleBean(HrmQueryController.class);
            });
    }

    /**
     * 来源为 none 时自动配置整体不生效，不注册配置、客户端和控制器。
     */
    @Test
    void registersNothingWhenSourceIsNone() {
        runner.withPropertyValues("org-sync.source=none")
            .run(ctx -> {
                assertThat(ctx).doesNotHaveBean(HrmClient.class);
                assertThat(ctx).doesNotHaveBean(HrmProperties.class);
                assertThat(ctx).doesNotHaveBean(HrmQueryController.class);
            });
    }

    /**
     * 创建客户端不校验白名单地址，缺少 hrm.client-ip 时应用仍能启动。
     */
    @Test
    void startsWithEmptyClientIp() {
        runner.withPropertyValues("org-sync.source=hrm", "hrm.client-ip=")
            .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /**
     * 组件扫描同样受来源条件约束：来源为 none 时不注册控制器，避免缺少查询服务导致启动失败。
     */
    @Test
    void componentScanSkipsControllerWhenSourceIsNone() {
        runner.withUserConfiguration(ControllerScanConfiguration.class)
            .withPropertyValues("org-sync.source=none")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(HrmQueryController.class);
            });
    }

    /**
     * 组件扫描与自动配置使用同一条件：来源为 hrm 时控制器正常注册。
     */
    @Test
    void componentScanRegistersControllerWhenSourceIsHrm() {
        runner.withUserConfiguration(ControllerScanConfiguration.class)
            .withPropertyValues("org-sync.source=hrm", "hrm.endpoint=http://127.0.0.1:1/services/HrmService")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(HrmQueryController.class);
            });
    }
}
