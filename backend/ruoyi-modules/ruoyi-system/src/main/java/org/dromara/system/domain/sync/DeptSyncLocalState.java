package org.dromara.system.domain.sync;

import org.dromara.system.domain.SysDept;
import java.util.*;

/** 同步计算使用的本地快照，不共享 Mapper 返回的可变对象。 */
public record DeptSyncLocalState(SysDept anchor, List<SysDept> departments, Set<Long> userDeptIds) {
    /** 复制记录与集合，隔离计划计算和调用方修改。 */
    public DeptSyncLocalState {
        anchor=DeptSyncPlan.copyRow(anchor);
        departments=departments.stream().map(DeptSyncPlan::copyRow).toList();
        userDeptIds=Set.copyOf(userDeptIds);
    }
}
