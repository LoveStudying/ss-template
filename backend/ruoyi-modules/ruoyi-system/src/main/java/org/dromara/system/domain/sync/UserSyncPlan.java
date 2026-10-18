package org.dromara.system.domain.sync;

import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.vo.UserSyncResultVo;

import java.util.List;
import java.util.Set;

/**
 * 待写入的员工同步计划。
 *
 * @param inserts         新增用户
 * @param updates         资料更新（含绑定），字段齐全
 * @param disables        离职停用，只含状态字段及读取时的原外部状态
 * @param affectedUserIds 需要清理缓存的已有用户 ID
 * @param result          同步结果
 */
public record UserSyncPlan(List<SysUser> inserts, List<SysUser> updates, List<Disable> disables,
                           Set<Long> affectedUserIds, UserSyncResultVo result) {

    /**
     * 一条停用计划。
     *
     * @param row               只含用户 ID、新外部状态、同步时间和停用状态的行
     * @param expectedHrmStatus 同步前读取到的 hrm_status，写入时作为乐观条件，读取后被他人修改则不覆盖
     */
    public record Disable(SysUser row, String expectedHrmStatus) {
    }
}
