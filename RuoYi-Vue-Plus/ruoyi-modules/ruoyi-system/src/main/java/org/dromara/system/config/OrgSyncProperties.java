package org.dromara.system.config;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 组织信息来源配置，决定装配哪一套组织同步实现。
 * <p>
 * 来源为 {@code hrm} 时加载泛微人力资源客户端；为 {@code none} 时不启用组织同步。
 * 其他取值在启动时校验失败，避免误把所有来源都当作未启用。
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "org-sync")
public class OrgSyncProperties {

    /** 来源标识：泛微 OA 人力资源。 */
    public static final String HRM = "hrm";

    /** 来源标识：不启用组织同步。 */
    public static final String NONE = "none";

    /** 组织信息来源，只能是 {@link #HRM} 或 {@link #NONE}，默认 {@link #HRM}。 */
    @Pattern(regexp = "hrm|none", message = "org-sync.source 只能为 hrm 或 none")
    private String source = HRM;
}
