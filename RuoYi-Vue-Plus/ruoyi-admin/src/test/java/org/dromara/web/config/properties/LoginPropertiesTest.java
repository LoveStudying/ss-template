package org.dromara.web.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class LoginPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsToSystemLogin() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("system", context.getBean(LoginProperties.class).getLoginMode());
        });
    }

    @Test
    void bindsSsoMode() {
        runner.withPropertyValues("auth.login-mode=sso").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("sso", context.getBean(LoginProperties.class).getLoginMode());
        });
    }

    @Test
    void rejectsInvalidModesAtStartup() {
        for (String mode : new String[]{"", "other", "SSO"}) {
            runner.withPropertyValues("auth.login-mode=" + mode).run(context ->
                assertNotNull(context.getStartupFailure()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoginProperties.class)
    static class TestConfiguration {
    }
}
