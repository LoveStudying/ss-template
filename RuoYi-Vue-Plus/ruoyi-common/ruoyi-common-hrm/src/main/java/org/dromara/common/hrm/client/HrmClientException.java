package org.dromara.common.hrm.client;

/** OA 客户端配置、网络或 SOAP 协议异常，不包含上游原始响应。 */
public class HrmClientException extends RuntimeException {

    /** 使用脱敏的错误说明创建异常。 */
    public HrmClientException(String message) {
        super(message);
    }

    /** 保留底层错误原因，响应正文不得放入错误说明。 */
    public HrmClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
