package org.dromara.system.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.dromara.system.constant.UserSyncConstants.SKIPPED;

/**
 * 员工同步结果统计。
 */
@Data
public class UserSyncResultVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    /** 本地部门 ID。 */
    private Long deptId;
    /** 使用的外部部门 ID。 */
    private String sourceDepartmentId;
    /** 外部来源返回的有效记录数（已去除空 ID 和重复 ID）。 */
    private int fetched;
    /** 新增数。 */
    private int added;
    /** 更新数（含绑定）。 */
    private int updated;
    /** 停用数。 */
    private int disabled;
    /** 跳过数。 */
    private int skipped;
    /** 提示列表。 */
    private List<UserSyncWarningVo> warnings = new ArrayList<>();

    /**
     * 追加一条提示，不改变计数。
     *
     * @param type      提示类型
     * @param hrmUserId 外部人员 ID
     * @param name      人员姓名
     * @param reason    中文说明
     */
    public void warn(String type, String hrmUserId, String name, String reason) {
        warnings.add(new UserSyncWarningVo(type, hrmUserId, name, reason));
    }

    /**
     * 记录一次跳过。
     *
     * @param hrmUserId 外部人员 ID
     * @param name      人员姓名
     * @param reason    跳过原因
     */
    public void skip(String hrmUserId, String name, String reason) {
        skipped++;
        warn(SKIPPED, hrmUserId, name, reason);
    }

    /** 新增计数加一。 */
    public void countAdded() {
        added++;
    }

    /** 更新计数加一。 */
    public void countUpdated() {
        updated++;
    }

    /** 停用计数加一。 */
    public void countDisabled() {
        disabled++;
    }
}
