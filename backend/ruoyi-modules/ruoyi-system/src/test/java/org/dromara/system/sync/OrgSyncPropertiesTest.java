package org.dromara.system.sync;

import org.dromara.system.config.OrgSyncProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证组织信息来源配置的默认值、允许取值与非法取值启动失败。
 */
@DisplayName("组织信息来源配置测试")
class OrgSyncPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class))
        .withUserConfiguration(OrgSyncProperties.class);

    /**
     * 未显式配置时默认使用 HRM 来源。
     */
    @Test
    void defaultsToHrm() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(OrgSyncProperties.class).getSource()).isEqualTo(OrgSyncProperties.HRM);
        });
    }

    /**
     * none 是允许的取值，表示不启用组织同步。
     */
    @Test
    void acceptsNone() {
        runner.withPropertyValues("org-sync.source=none")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(OrgSyncProperties.class).getSource()).isEqualTo(OrgSyncProperties.NONE);
            });
    }

    /**
     * 未知来源必须让应用启动失败，并在错误信息中说明可选值。
     */
    @Test
    void rejectsUnknownSource() {
        runner.withPropertyValues("org-sync.source=ldap")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx).getFailure().hasStackTraceContaining("org-sync.source 只能为 hrm 或 none");
            });
    }
}
