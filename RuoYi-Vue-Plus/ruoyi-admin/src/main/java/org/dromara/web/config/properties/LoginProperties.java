package org.dromara.web.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 管理后台默认登录入口配置，保留显式本地账号登录能力。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "auth")
public class LoginProperties {

    /**
     * 默认登录模式：system 为本地登录页，sso 为三生统一登录。
     */
    @NotBlank
    @Pattern(regexp = "system|sso", message = "登录模式必须为 system 或 sso")
    private String loginMode = "system";
}
