package org.dromara.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.sync.*;
import org.dromara.system.domain.vo.DeptSyncResultVo;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import static org.dromara.system.constant.DeptSyncConstants.*;

/** 全部后代的身份、树关系和最终状态计算，无网络、数据库或缓存副作用。 */
@Component
public class DeptSyncPlanner {
    /**
     * 生成原子写入计划，跳过停用节点及其子树，点击节点只更新成功时间。
     *
     * @param snapshot 组织来源的已校验分部快照
     * @param local    本地相关部门与直接用户关联快照
     * @param syncTime 本次同步时间
     * @return 无副作用的写入计划
     */
    public DeptSyncPlan plan(OrgDepartmentSnapshot snapshot, DeptSyncLocalState local, LocalDateTime syncTime) {
        SysDept anchor=local.anchor();
        if (!isSupportedType(anchor.getDeptType()) || anchor.getBusinessId()==null || !"0".equals(anchor.getDelFlag())
            || !snapshot.subcompanyId().equals(anchor.getHrmSubcompanyId())) { throw conflict(anchor,"锚点绑定不完整或已变化"); }
        Map<Long,SysDept> before=new LinkedHashMap<>(); Map<Long,SysDept> tree=new LinkedHashMap<>();
        Map<String,SysDept> identity=new HashMap<>();
        for (SysDept row : local.departments()) {
            before.put(row.getDeptId(),DeptSyncPlan.copyRow(row));
            if ("0".equals(row.getDelFlag())) { tree.put(row.getDeptId(),DeptSyncPlan.copyRow(row)); }
            if (DEPARTMENT.equals(row.getDeptType()) && row.getBusinessId()!=null
                && identity.putIfAbsent(row.getBusinessId(),row)!=null) { throw conflict(row,"本地业务身份重复"); }
        }
        if (!tree.containsKey(anchor.getDeptId()) || !Objects.equals(before.get(anchor.getDeptId()),anchor)) {
            throw conflict(anchor,"点击节点快照不一致");
        }
        List<OrgDepartment> selected;
        if (SUBCOMPANY.equals(anchor.getDeptType())) {
            if (!anchor.getBusinessId().equals(snapshot.subcompanyId())) { throw conflict(anchor,"分部绑定不一致"); }
            selected=snapshot.departments();
        } else {
            OrgDepartment upstream=snapshot.departments().stream().filter(n -> n.id().equals(anchor.getBusinessId())).findFirst()
                .orElseThrow(() -> conflict(anchor,"组织来源锚点不存在"));
            SysDept parent=tree.get(anchor.getParentId());
            String parentBusiness=parent==null ? null : SUBCOMPANY.equals(parent.getDeptType()) ? "0" : parent.getBusinessId();
            if (!upstream.parentId().equals(parentBusiness)
                || !upstream.parentId().equals(anchor.getHrmSupdepartmentId())) { throw conflict(anchor,"锚点上级已变化，请同步上级"); }
            selected=upstream.disabled() ? List.of() : snapshot.descendants(anchor.getBusinessId());
        }
        Set<String> skipped=new HashSet<>(); List<OrgDepartment> enabled=new ArrayList<>();
        // 快照按父先子后排序，跳过整棵子树以避免子部门缺失父节点或被重新挂载。
        for (OrgDepartment node : selected) {
            if (node.disabled() || skipped.contains(node.parentId())) {
                skipped.add(node.id());
            } else { enabled.add(node); }
        }
        selected=enabled;
        Map<String,Long> mapping=new HashMap<>();
        if (DEPARTMENT.equals(anchor.getDeptType())) { mapping.put(anchor.getBusinessId(),anchor.getDeptId()); }
        Set<Long> selectedIds=new LinkedHashSet<>(); Set<Long> newIds=new HashSet<>();
        Set<String> companies=new HashSet<>(); companies.add(snapshot.subcompanyId());
        DeptSyncResultVo result=new DeptSyncResultVo(); result.setDeptId(anchor.getDeptId());
        result.setSubcompanyId(snapshot.subcompanyId()); result.setSyncTime(syncTime);
        result.setFetchedCount(snapshot.departments().size()); result.setSelectedCount(selected.size());
        for (OrgDepartment node : selected) {
            SysDept old=identity.get(node.id());
            if (old!=null && !"0".equals(old.getDelFlag())) { throw conflict(old,"身份被已删除部门占用"); }
            SysDept row;
            if (old==null) {
                row=new SysDept(); row.setDeptId(IdWorker.getId()); row.setDelFlag("0"); row.setDeptType(DEPARTMENT);
                row.setBusinessId(node.id()); newIds.add(row.getDeptId());
            } else {
                row=DeptSyncPlan.copyRow(old);
                if (old.getHrmSubcompanyId()!=null) { companies.add(old.getHrmSubcompanyId()); }
            }
            Long parentId="0".equals(node.parentId()) ? anchor.getDeptId() : mapping.get(node.parentId());
            if (parentId==null) { throw conflict(row,"父节点不在本次范围"); }
            row.setParentId(parentId); row.setDeptName(node.name()); row.setOrderNum(node.orderNum());
            row.setHrmSubcompanyId(node.subcompanyId()); row.setHrmSupdepartmentId(node.parentId()); row.setHrmSupsubcompanyId(null);
            row.setHrmCode(node.code()); row.setHrmFullName(node.fullName()); row.setHrmCanceled(node.rawStatus());
            row.setHrmLastChangeTime(node.lastChangeTime()); row.setLastSyncTime(syncTime);
            String desired=node.disabled() ? "1" : "0";
            if (old!=null && local.userDeptIds().contains(row.getDeptId())) {
                if (!desired.equals(old.getStatus())) {
                    result.addWarning(row.getBusinessId(),HAS_USERS,old.getStatus(),node.rawStatus());
                    result.setStatusSkippedCount(result.getStatusSkippedCount()+1);
                }
            } else { row.setStatus(desired); }
            tree.put(row.getDeptId(),row); mapping.put(node.id(),row.getDeptId()); selectedIds.add(row.getDeptId());
        }
        Map<Long,List<SysDept>> children=new HashMap<>();
        tree.values().forEach(row -> children.computeIfAbsent(row.getParentId(),k -> new ArrayList<>()).add(row));
        Deque<SysDept> queue=new ArrayDeque<>(children.getOrDefault(0L,List.of())); List<SysDept> topology=new ArrayList<>();
        while (!queue.isEmpty()) {
            SysDept row=queue.removeFirst(); SysDept parent=tree.get(row.getParentId());
            String path=parent==null ? "0" : parent.getAncestors()+","+parent.getDeptId();
            if (path.length()>500) { throw conflict(row,"祖先路径超长"); }
            row.setAncestors(path); topology.add(row); queue.addAll(children.getOrDefault(row.getDeptId(),List.of()));
        }
        if (topology.size()!=tree.size()) { throw conflict(anchor,"本地树存在环或缺失祖先"); }
        Set<Long> activeDescendant=new HashSet<>();
        for (int i=topology.size()-1;i>=0;i--) {
            SysDept row=topology.get(i);
            if (selectedIds.contains(row.getDeptId()) && "1".equals(row.getStatus()) && activeDescendant.contains(row.getDeptId())) {
                SysDept old=before.get(row.getDeptId());
                if (old!=null && "0".equals(old.getStatus()) && !local.userDeptIds().contains(row.getDeptId())) {
                    row.setStatus("0"); result.addWarning(row.getBusinessId(),ACTIVE_DESCENDANT,"0",row.getHrmCanceled());
                    result.setStatusSkippedCount(result.getStatusSkippedCount()+1);
                } else { throw conflict(row,"停用部门下存在正常后代"); }
            }
            if ("0".equals(row.getStatus()) || activeDescendant.contains(row.getDeptId())) { activeDescendant.add(row.getParentId()); }
        }
        tree.get(anchor.getDeptId()).setLastSyncTime(syncTime);
        List<SysDept> inserts=new ArrayList<>(); List<DeptSyncPlan.Update> updates=new ArrayList<>(); Set<Long> affected=new HashSet<>();
        for (SysDept row : topology) {
            SysDept old=before.get(row.getDeptId());
            boolean pathChanged=old!=null && !Objects.equals(row.getAncestors(),old.getAncestors());
            if (selectedIds.contains(row.getDeptId()) || pathChanged || row.getDeptId().equals(anchor.getDeptId())) {
                affected.add(row.getDeptId());
                if (DEPARTMENT.equals(row.getDeptType())) {
                    SysDept company=nearestCompany(row,tree);
                    if (!Objects.equals(company.getBusinessId(),row.getHrmSubcompanyId())) { throw conflict(row,"绑定后代分部无法一致对齐"); }
                }
                SysDept cursor=row;
                while (cursor!=null) {
                    if ("0".equals(row.getStatus()) && "1".equals(cursor.getStatus())) { throw conflict(row,"正常部门落在停用祖先下"); }
                    cursor=tree.get(cursor.getParentId());
                }
                if (newIds.contains(row.getDeptId())) { inserts.add(row); }
                else { updates.add(new DeptSyncPlan.Update(old,row)); }
            }
            if (selectedIds.contains(row.getDeptId())) {
                if (newIds.contains(row.getDeptId())) { result.setCreatedCount(result.getCreatedCount()+1); }
                else if (businessEquals(old,row)) { result.setUnchangedCount(result.getUnchangedCount()+1); }
                else { result.setUpdatedCount(result.getUpdatedCount()+1); }
            }
        }
        for (SysDept row : tree.values()) {
            if (!affected.contains(row.getDeptId())) { continue; }
            for (SysDept sibling : children.getOrDefault(row.getParentId(),List.of())) {
                if (!sibling.getDeptId().equals(row.getDeptId()) && row.getDeptName().equalsIgnoreCase(sibling.getDeptName())) {
                    throw conflict(row,"同父级部门名称冲突");
                }
            }
        }
        tree.values().stream().filter(d -> SUBCOMPANY.equals(d.getDeptType()) && companies.contains(d.getBusinessId()))
            .forEach(d -> affected.add(d.getDeptId()));
        return new DeptSyncPlan(inserts,updates,before,local.userDeptIds(),affected,companies,result);
    }

    private SysDept nearestCompany(SysDept row, Map<Long,SysDept> tree) {
        SysDept cursor=tree.get(row.getParentId());
        while (cursor!=null) {
            if (SUBCOMPANY.equals(cursor.getDeptType())) { return cursor; }
            cursor=tree.get(cursor.getParentId());
        }
        throw conflict(row,"缺少本地分部祖先");
    }

    private boolean businessEquals(SysDept before, SysDept after) {
        SysDept a=DeptSyncPlan.copyRow(before); SysDept b=DeptSyncPlan.copyRow(after);
        a.setLastSyncTime(null); b.setLastSyncTime(null); return a.equals(b);
    }

    private static ServiceException conflict(SysDept row, String reason) {
        return new ServiceException("组织来源节点 " + (row.getBusinessId()==null ? "未绑定" : row.getBusinessId()) + "：" + reason);
    }
}
