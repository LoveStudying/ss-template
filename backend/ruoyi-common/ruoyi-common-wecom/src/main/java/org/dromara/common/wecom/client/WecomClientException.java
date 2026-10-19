package org.dromara.common.wecom.client;

/** 企业微信调用失败，仅保留操作名与错误码，不携带原始响应、请求地址或凭据。 */
public class WecomClientException extends RuntimeException {

    /** 企业微信业务错误码，网络或 SDK 运行失败时为 null。 */
    private final Integer errorCode;

    /** 失败类型，仅为异常类的简单名称，不包含原始错误消息。 */
    private final String failureType;

    /**
     * 创建脱敏异常；不保留可能包含访问令牌和用户信息的 SDK 异常链。
     *
     * @param operation 固定的业务操作名称，不得传入用户数据
     * @param errorCode 企业微信错误码，非业务错误时为 null
     */
    public WecomClientException(String operation, Integer errorCode) {
        this(operation, errorCode, null);
    }

    /**
     * 创建携带安全诊断类型的脱敏异常。
     *
     * @param operation 固定的业务操作名称
     * @param errorCode 企业微信错误码，非业务错误时为 null
     * @param failureType 仅传异常类简单名称，不得包含消息、请求或响应内容
     */
    public WecomClientException(String operation, Integer errorCode, String failureType) {
        super(operation + (errorCode == null ? "失败：网络或 SDK 异常" : "失败：企业微信错误码 " + errorCode));
        this.errorCode = errorCode;
        this.failureType = failureType;
    }

    /**
     * 获取企业微信业务错误码，用于区分凭据、权限及 IP 白名单问题。
     *
     * @return 业务错误码，非业务错误时为 null
     */
    public Integer getErrorCode() {
        return errorCode;
    }

    /**
     * 获取不含敏感内容的异常类型，便于区分 DNS、TLS 和连接超时等问题。
     *
     * @return 原始异常的类简单名称；业务错误时可能为 null
     */
    public String getFailureType() {
        return failureType;
    }
}
