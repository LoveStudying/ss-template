package org.dromara.common.hrm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 泛微人力资源只读客户端配置，调用前必须指定服务地址和白名单客户端 IP。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hrm")
public class HrmProperties {

    /** SOAP 服务地址，不包含 ?wsdl。 */
    private String endpoint;

    /** 作为 in0 传入 OA 的客户端 IPv4 地址，需由接口方加入白名单。 */
    private String clientIp;

    /** 建立连接的超时秒数，必须大于零。 */
    private int connectTimeoutSeconds = 10;

    /** 单次请求的超时秒数，必须大于零。 */
    private int readTimeoutSeconds = 30;

    /** 由配置文件控制脱敏响应 XML 日志，未配置时关闭，诊断结束后应关闭。 */
    private boolean logResponseBody;
}
