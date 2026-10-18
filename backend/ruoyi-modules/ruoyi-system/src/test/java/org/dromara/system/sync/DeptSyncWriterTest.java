package org.dromara.system.sync;

import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.sync.*;
import org.dromara.system.mapper.*;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.impl.*;
import org.junit.jupiter.api.*;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证部门同步写入的事务重验、数据权限与提交后缓存失效。 */
class DeptSyncWriterTest {
    private final SysDeptMapper depts=mock(SysDeptMapper.class);
    private final SysUserMapper users=mock(SysUserMapper.class);
    private final ISysDeptService scope=mock(ISysDeptService.class);
    private final CacheManager caches=new ConcurrentMapCacheManager(CacheNames.SYS_DEPT,CacheNames.SYS_DEPT_AND_CHILD);
    private final DeptSyncPlan plan=new DeptSyncPlanner().plan(new OrgDepartmentSnapshot("5",List.of()),
        new DeptSyncLocalState(DeptSyncPlannerTest.company(),List.of(DeptSyncPlannerTest.company()),Set.of()),LocalDateTime.now());

    @BeforeEach void transaction() {
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        when(depts.lockSourceDepartmentRows(any())).thenReturn(new ArrayList<>(plan.expectedRows().values()));
        when(depts.lockSourceChildren(any())).thenAnswer(call -> {
            Collection<Long> parents=call.getArgument(0);
            return plan.expectedRows().values().stream().filter(d -> parents.contains(d.getParentId())).toList();
        });
        when(depts.updateSourceSnapshot(any())).thenReturn(1);
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test void writerRequiresRepeatableReadTransaction() throws Exception {
        var tx=DeptSyncWriter.class.getMethod("write",DeptSyncPlan.class).getAnnotation(Transactional.class);
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }

    @Test void staleRowsAndUsersRejectPlanBeforeWriting() {
        var changed=DeptSyncPlan.copyRow(plan.expectedRows().get(100L)); changed.setDeptName("并发编辑");
        when(depts.lockSourceDepartmentRows(any())).thenReturn(List.of(changed));
        DeptSyncWriter writer=new DeptSyncWriter(depts,users,scope,caches);
        assertThatThrownBy(() -> writer.write(plan)).isInstanceOf(ServiceException.class);
        when(depts.lockSourceDepartmentRows(any())).thenReturn(new ArrayList<>(plan.expectedRows().values()));
        when(users.lockUserDepartments(any())).thenReturn(List.of(100L));
        assertThatThrownBy(() -> writer.write(plan)).isInstanceOf(ServiceException.class);
        verify(depts,never()).updateSourceSnapshot(any());
    }

    @Test void clearsCachesOnlyInCommitCallback() {
        caches.getCache(CacheNames.SYS_DEPT).put(100L,"old"); caches.getCache(CacheNames.SYS_DEPT_AND_CHILD).put(100L,"old-tree");
        new DeptSyncWriter(depts,users,scope,caches).write(plan);
        assertThat(caches.getCache(CacheNames.SYS_DEPT).get(100L)).isNotNull();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        assertThat(caches.getCache(CacheNames.SYS_DEPT).get(100L)).isNull();
        assertThat(caches.getCache(CacheNames.SYS_DEPT_AND_CHILD).get(100L)).isNull();
    }

    @Test void cacheFailureAfterCommitDoesNotReportRollback() {
        CacheManager broken=mock(CacheManager.class); Cache cache=mock(Cache.class);
        when(broken.getCache(anyString())).thenReturn(cache); doThrow(new IllegalStateException("test cache failure")).when(cache).evict(any());
        var result=new DeptSyncWriter(depts,users,scope,broken).write(plan);
        assertThat(result.getWarningCount()).isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        assertThat(result.getWarnings()).extracting(w -> w.getReason()).contains("CACHE_REFRESH_FAILED");
        assertThat(result.getStatusSkippedCount()).isZero();
    }

    @Test void unexpectedUpdateCountFailsWithoutRegisteringSuccessfulCommitCallback() {
        when(depts.updateSourceSnapshot(any())).thenReturn(0);
        assertThatThrownBy(() -> new DeptSyncWriter(depts,users,scope,caches).write(plan)).isInstanceOf(ServiceException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test void movedDepartmentAndManualDescendantDataScopeCannotBeBypassed() {
        doThrow(new ServiceException("无数据权限")).when(scope).checkDeptDataScope(100L);
        assertThatThrownBy(() -> new DeptSyncWriter(depts,users,scope,caches).write(plan)).hasMessageContaining("无数据权限");
        verify(depts,never()).updateSourceSnapshot(any());
    }
}
