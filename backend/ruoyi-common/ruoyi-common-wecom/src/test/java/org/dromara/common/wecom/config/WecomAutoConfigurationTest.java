package org.dromara.common.wecom.config;

import me.chanjar.weixin.cp.api.WxCpService;
import org.dromara.common.wecom.client.WecomClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WecomAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WecomAutoConfiguration.class))
        .withPropertyValues("wecom.corp-id=", "wecom.corp-secret=", "wecom.oauth-agent-id=");

    @Test
    void disabledByDefaultWithoutCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WecomClient.class);
            assertThat(context).doesNotHaveBean(WxCpService.class);
        });
    }

    @Test
    void registersSdkAndClientWithoutCallingNetwork() {
        runner.withPropertyValues("wecom.enabled=true", "wecom.corp-id=test-corp",
            "wecom.corp-secret=test-secret", "wecom.oauth-agent-id=42").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(WecomClient.class);
                assertThat(context).hasSingleBean(WxCpService.class);
                var config = context.getBean(WxCpService.class).getWxCpConfigStorage();
                assertThat(config.getCorpId()).isEqualTo("test-corp");
                assertThat(config.getAgentId()).isEqualTo(42);
            });
    }

    @Test
    void rejectsMissingCredentialsOnlyWhenEnabled() {
        runner.withPropertyValues("wecom.enabled=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("wecom.enabled=false").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void doesNotRequireAgentIdForDirectoryQueries() {
        runner.withPropertyValues("wecom.enabled=true", "wecom.corp-id=test-corp",
            "wecom.corp-secret=test-secret").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsNonPositiveAgentIdWhenConfigured() {
        runner.withPropertyValues("wecom.enabled=true", "wecom.corp-id=test-corp",
            "wecom.corp-secret=test-secret", "wecom.oauth-agent-id=0")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void allowsCallerProvidedSdkWithoutDefaultCredentials() {
        WxCpService sdk = mock(WxCpService.class);
        runner.withBean(WxCpService.class, () -> sdk).withPropertyValues("wecom.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(WxCpService.class)).isSameAs(sdk);
                assertThat(context).hasSingleBean(WecomClient.class);
            });
    }
}
