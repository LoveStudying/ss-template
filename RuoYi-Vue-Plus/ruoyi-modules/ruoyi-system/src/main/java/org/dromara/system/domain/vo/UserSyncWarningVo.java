package org.dromara.system.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 员工同步单条提示，不含手机号、邮箱等联系方式。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSyncWarningVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    /** 提示类型，取值见 UserSyncConstants。 */
    private String type;
    /** 外部人员 ID，字段名沿用 hrmUserId 与前端类型保持一致。 */
    private String hrmUserId;
    /** 人员姓名或本地昵称。 */
    private String name;
    /** 中文说明。 */
    private String reason;
}
