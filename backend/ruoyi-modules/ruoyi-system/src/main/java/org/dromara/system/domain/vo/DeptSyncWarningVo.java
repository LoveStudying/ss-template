package org.dromara.system.domain.vo;

import lombok.Data;
import java.io.Serializable;

/** 部门同步警告摘要。 */
@Data
public class DeptSyncWarningVo implements Serializable {
    /** 节点业务 ID。 */
    private String businessId;

    /** 状态保护或提交后告警原因。 */
    private String reason;

    /** 本地状态。 */
    private String localStatus;

    /** 组织来源原始状态，字段名沿用 sys_dept.hrm_canceled。 */
    private String hrmCanceled;

}
