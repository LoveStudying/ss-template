package org.dromara.web.domain.vo;

import lombok.Data;

/**
 * 未登录时可获取的公开登录入口配置，不包含 SSO 客户端凭据。
 */
@Data
public class LoginConfigVo {

    /**
     * 默认登录模式：system 为本地登录，sso 为三生统一登录。
     */
    private String mode;
}
