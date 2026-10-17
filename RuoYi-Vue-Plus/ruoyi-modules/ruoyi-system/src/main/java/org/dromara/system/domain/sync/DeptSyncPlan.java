package org.dromara.system.domain.sync;

import cn.hutool.core.bean.BeanUtil;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.vo.DeptSyncResultVo;
import java.util.*;

/** 无写入的完整同步计划，包含事务重验前置快照。 */
public record DeptSyncPlan(List<SysDept> inserts, List<Update> updates, Map<Long,SysDept> expectedRows,
                          Set<Long> expectedUserDeptIds, Set<Long> affectedDeptIds,
                          Set<String> requiredSubcompanyIds, DeptSyncResultVo result) {
    /** 复制计划数据，集合不允许外部增删。 */
    public DeptSyncPlan {
        inserts=inserts.stream().map(DeptSyncPlan::copyRow).toList();
        updates=List.copyOf(updates);
        Map<Long,SysDept> rows=new LinkedHashMap<>(); expectedRows.forEach((id,row) -> rows.put(id,copyRow(row)));
        expectedRows=Collections.unmodifiableMap(rows); expectedUserDeptIds=Set.copyOf(expectedUserDeptIds);
        affectedDeptIds=Set.copyOf(affectedDeptIds); requiredSubcompanyIds=Set.copyOf(requiredSubcompanyIds);
    }

    /** 单条记录修改前后的独立副本。 */
    public record Update(SysDept before, SysDept after) {
        /** 隔离数据库实体和计划中的可变对象。 */
        public Update { before=copyRow(before); after=copyRow(after); }
    }

    /** 复制一条数据库记录，不携带树视图中的子节点对象。 */
    public static SysDept copyRow(SysDept row) {
        SysDept copy=BeanUtil.copyProperties(row,SysDept.class); copy.setChildren(new ArrayList<>()); return copy;
    }
}
