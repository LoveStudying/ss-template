package org.dromara.system.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.sync.*;
import org.dromara.system.domain.vo.DeptSyncResultVo;
import org.dromara.system.mapper.*;
import org.dromara.system.service.ISysDeptService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.dromara.system.constant.DeptSyncConstants.*;

/** 同步的独立事务边界，网络拉取由应用服务在事务外完成。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeptSyncWriter {
    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final ISysDeptService deptService;
    private final CacheManager cacheManager;

    /**
     * 收集全局身份命中及其父级、同级、祖先和后代，包含删除身份。
     *
     * @param deptId   点击部门本地主键
     * @param snapshot 组织来源分部快照
     * @return 本地相关部门与直接用户关联快照
     */
    public DeptSyncLocalState readState(Long deptId, OrgDepartmentSnapshot snapshot) {
        deptService.checkDeptDataScope(deptId);
        SysDept anchor=deptMapper.selectById(deptId);
        if (anchor==null) { throw retry("点击部门不存在"); }
        List<String> businessIds=snapshot.departments().stream().map(OrgDepartment::id).toList();
        Map<Long,SysDept> rows=new LinkedHashMap<>(); rows.put(deptId,anchor);
        if (!businessIds.isEmpty()) { deptMapper.selectIdentityRows(businessIds).forEach(d -> rows.put(d.getDeptId(),d)); }
        Set<Long> seeds=new TreeSet<>();
        Set<Long> queried=new HashSet<>();
        do {
            rows.values().forEach(d -> {
                seeds.add(d.getDeptId());
                if (d.getParentId()!=null && d.getParentId()!=0) { seeds.add(d.getParentId()); }
                if (d.getAncestors()!=null) {
                    for (String id : d.getAncestors().split(",")) { if (!id.isBlank() && !"0".equals(id)) { seeds.add(Long.valueOf(id)); } }
                }
            });
            List<Long> next=seeds.stream().filter(id -> !queried.contains(id)).toList();
            if (next.isEmpty()) { break; }
            queried.addAll(next);
            deptMapper.selectSourceRelatedRows(next).forEach(d -> rows.put(d.getDeptId(),d));
        } while (true);
        Set<Long> users=new HashSet<>(userMapper.selectUserDepartments(rows.keySet()));
        return new DeptSyncLocalState(anchor,new ArrayList<>(rows.values()),users);
    }

    /**
     * 按当前读重验记录、子树和直接用户范围，再一次性写入；缓存只在提交后失效。
     *
     * @param plan 同步计划
     * @return 同步结果，缓存失效失败时提交后追加告警
     */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.REPEATABLE_READ)
    public DeptSyncResultVo write(DeptSyncPlan plan) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("同步写入必须通过事务代理"); }
        checkScope(plan);
        List<Long> ids=plan.expectedRows().keySet().stream().sorted().toList();
        Map<Long,SysDept> locked=byId(deptMapper.lockSourceDepartmentRows(ids));
        if (!locked.equals(plan.expectedRows())) { throw retry("部门记录已变更"); }
        Set<Long> parents=new TreeSet<>(ids);
        plan.expectedRows().values().forEach(d -> { if (d.getParentId()!=null) { parents.add(d.getParentId()); } });
        plan.inserts().forEach(d -> parents.add(d.getParentId()));
        parents.remove(0L);
        Map<Long,SysDept> children=byId(deptMapper.lockSourceChildren(parents));
        Map<Long,SysDept> expectedChildren=new LinkedHashMap<>();
        plan.expectedRows().values().stream().filter(d -> "0".equals(d.getDelFlag()) && parents.contains(d.getParentId()))
            .forEach(d -> expectedChildren.put(d.getDeptId(),d));
        if (!children.equals(expectedChildren)) { throw retry("子部门结构已变更"); }
        Set<Long> userRanges=new TreeSet<>(ids);
        plan.inserts().forEach(d -> userRanges.add(d.getDeptId()));
        Set<Long> users=new HashSet<>(userMapper.lockUserDepartments(userRanges));
        if (!users.equals(plan.expectedUserDeptIds())) { throw retry("部门用户关联已变更"); }
        for (SysDept row : plan.inserts()) { if (deptMapper.insert(row)!=1) { throw retry("新增部门失败"); } }
        for (DeptSyncPlan.Update update : plan.updates()) {
            if (deptMapper.updateSourceSnapshot(update.after())!=1) { throw retry("更新部门数量异常"); }
        }
        if (!deptMapper.selectSourceNameConflicts(plan.affectedDeptIds()).isEmpty()) { throw retry("同父级部门名称冲突"); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { invalidateAfterCommit(plan); }
        });
        return plan.result();
    }

    private void checkScope(DeptSyncPlan plan) {
        Set<Long> scope=new TreeSet<>();
        plan.updates().forEach(u -> {
            scope.add(u.before().getDeptId()); scope.add(u.before().getParentId()); scope.add(u.after().getParentId());
        });
        plan.inserts().forEach(d -> scope.add(d.getParentId()));
        scope.stream().filter(id -> id!=0 && plan.expectedRows().containsKey(id)).forEach(deptService::checkDeptDataScope);
    }

    private void invalidateAfterCommit(DeptSyncPlan plan) {
        boolean failed=false;
        // 单个 key 失败仍尝试其余缓存，不能因首个异常遗漏其他已提交节点。
        for (Long id : plan.affectedDeptIds()) {
            try { requiredCache(CacheNames.SYS_DEPT).evict(id); }
            catch (RuntimeException e) { failed=true; log.error("部门同步已提交，缓存失效失败 deptId={} causeType={}",id,e.getClass().getSimpleName()); }
        }
        try { requiredCache(CacheNames.SYS_DEPT_AND_CHILD).clear(); }
        catch (RuntimeException e) { failed=true; log.error("部门同步已提交，部门树缓存刷新失败 causeType={}",e.getClass().getSimpleName()); }
        if (failed) {
            SysDept anchor=plan.expectedRows().get(plan.result().getDeptId());
            plan.result().addWarning(anchor.getBusinessId(),CACHE_REFRESH_FAILED,anchor.getStatus(),anchor.getHrmCanceled());
        }
    }

    private Cache requiredCache(String name) {
        Cache cache=cacheManager.getCache(name);
        if (cache==null) { throw new IllegalStateException("部门缓存未配置"); } return cache;
    }
    private Map<Long,SysDept> byId(List<SysDept> rows) {
        Map<Long,SysDept> result=new LinkedHashMap<>(); rows.forEach(d -> result.put(d.getDeptId(),DeptSyncPlan.copyRow(d))); return result;
    }
    private static ServiceException retry(String reason) { return new ServiceException(reason+"，请刷新后重试"); }
}
