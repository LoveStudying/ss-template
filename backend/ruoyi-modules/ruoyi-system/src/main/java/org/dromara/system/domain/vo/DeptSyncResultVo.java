package org.dromara.system.domain.vo;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 部门同步提交结果与统计。 */
@Data
public class DeptSyncResultVo implements Serializable {
    /** 点击节点本地主键。 */
    private Long deptId;

    /** 拉取分部业务 ID。 */
    private String subcompanyId;

    /** 成功同步时间。 */
    private LocalDateTime syncTime;

    /** 完整快照数量。 */
    private int fetchedCount;

    /** 排除停用节点及其子树后，实际参与同步的后代数量。 */
    private int selectedCount;

    /** 新增数量。 */
    private int createdCount;

    /** 业务变更数量。 */
    private int updatedCount;

    /** 业务未变化数量。 */
    private int unchangedCount;

    /** 状态保护节点数量。 */
    private int statusSkippedCount;

    /** 完整警告数量。 */
    private int warningCount;

    /** 警告是否截断。 */
    private boolean warningsTruncated;

    /** 最多二十条警告摘要。 */
    private List<DeptSyncWarningVo> warnings = new ArrayList<>();

    /** 记录完整数量并限制返回摘要，缓存告警不改变状态保护计数。 */
    public void addWarning(String businessId, String reason, String localStatus, String hrmCanceled) {
        warningCount++;
        if (warnings.size() < 20) {
            DeptSyncWarningVo warning = new DeptSyncWarningVo();
            warning.setBusinessId(businessId);
            warning.setReason(reason);
            warning.setLocalStatus(localStatus);
            warning.setHrmCanceled(hrmCanceled);
            warnings.add(warning);
        }
        warningsTruncated = warningCount > warnings.size();
    }
}
