package org.dromara.system.sync;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.sync.*;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.OrgSyncSource;
import org.dromara.system.service.impl.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证部门子树同步编排：来源前置检查、权限、锁、事务外拉取与重算。 */
class DeptSyncServiceTest {
    private final SysDeptMapper depts=mock(SysDeptMapper.class);
    private final ISysDeptService scope=mock(ISysDeptService.class);
    private final OrgSyncSourceProvider provider=mock(OrgSyncSourceProvider.class);
    private final OrgSyncSource source=mock(OrgSyncSource.class);
    private final DeptSyncWriter writer=mock(DeptSyncWriter.class);
    private final DeptSyncLock locks=mock(DeptSyncLock.class);
    private final DeptSyncLock.Lease lease=mock(DeptSyncLock.Lease.class);
    private final SysDeptSyncServiceImpl service=new SysDeptSyncServiceImpl(depts,scope,provider,new DeptSyncPlanner(),writer,locks);
    private MockedStatic<StpUtil> auth;
    @BeforeEach void setup() {
        auth=mockStatic(StpUtil.class); when(depts.selectById(100L)).thenReturn(DeptSyncPlannerTest.company());
        when(provider.require()).thenReturn(source);
        when(locks.tryAcquire(any())).thenReturn(lease); when(writer.write(any())).thenAnswer(call -> ((DeptSyncPlan)call.getArgument(0)).result());
    }
    @AfterEach void close() { auth.close(); }

    @Test void failsFastWhenSourceDisabled() {
        when(provider.require()).thenThrow(new ServiceException("未启用组织信息来源，无法同步"));
        assertThatThrownBy(() -> service.syncChildren(100L)).isInstanceOf(ServiceException.class)
            .hasMessage("未启用组织信息来源，无法同步");
        verifyNoInteractions(depts,locks,writer,scope);
    }

    @Test void discoversOldCompanyReacquiresAndReloads() {
        SysDept existing=DeptSyncPlannerTest.child(101,"1","0"); existing.setHrmSubcompanyId("6");
        SysDept oldCompany=DeptBindingValidatorTest.dept(200,"subcompany","6"); oldCompany.setHrmSubcompanyId("6");
        existing.setParentId(200L); existing.setAncestors("0,200");
        var local=new DeptSyncLocalState(DeptSyncPlannerTest.company(),List.of(DeptSyncPlannerTest.company(),oldCompany,existing),Set.of());
        when(source.listDepartments("5")).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return DeptSyncPlannerTest.snapshot(DeptSyncPlannerTest.node("1","0","0"));
        });
        when(writer.readState(eq(100L),any())).thenReturn(local);
        var result=service.syncChildren(100L); assertThat(result.getUpdatedCount()).isEqualTo(1);
        verify(locks).tryAcquire(Set.of("5")); verify(locks).tryAcquire(Set.of("5","6"));
        verify(source,times(2)).listDepartments("5"); verify(writer,times(2)).readState(eq(100L),any());
        verify(writer,times(1)).write(any()); verify(lease,times(2)).close();
    }

    @Test void ordinaryDepartmentUsesPersistedCompanyAndNotItsOwnBusinessId() {
        SysDept anchor=DeptSyncPlannerTest.child(101,"2976","0"); when(depts.selectById(101L)).thenReturn(anchor);
        when(source.listDepartments("5")).thenReturn(DeptSyncPlannerTest.snapshot(DeptSyncPlannerTest.node("2976","0","0")));
        when(writer.readState(eq(101L),any())).thenReturn(new DeptSyncLocalState(anchor,List.of(DeptSyncPlannerTest.company(),anchor),Set.of()));
        assertThat(service.syncChildren(101L).getSelectedCount()).isZero(); verify(source).listDepartments("5");
        verify(source,never()).listDepartments("2976");
    }

    @Test void disabledAnchorReturnsEmptySelectionAndStillSucceeds() {
        SysDept anchor=DeptSyncPlannerTest.child(101,"2976","0"); when(depts.selectById(101L)).thenReturn(anchor);
        when(source.listDepartments("5")).thenReturn(DeptSyncPlannerTest.snapshot(
            DeptSyncPlannerTest.node("2976","0","1"),DeptSyncPlannerTest.node("2977","2976","0")));
        when(writer.readState(eq(101L),any())).thenReturn(new DeptSyncLocalState(anchor,List.of(DeptSyncPlannerTest.company(),anchor),Set.of()));
        var result=service.syncChildren(101L);
        assertThat(result.getSelectedCount()).isZero(); assertThat(result.getCreatedCount()).isZero();
        verify(writer).write(any()); verify(lease).close();
    }

    @Test void incompleteBindingIsRejectedWithSourceWording() {
        SysDept unbound=DeptBindingValidatorTest.dept(102,null,null); when(depts.selectById(102L)).thenReturn(unbound);
        assertThatThrownBy(() -> service.syncChildren(102L)).isInstanceOf(ServiceException.class).hasMessage("部门未完整配置组织来源绑定");
        verifyNoInteractions(locks,source);
    }

    @Test void upstreamAndWriterFailuresReleaseLocksAndPermissionDenialDoesNotReadSource() {
        when(source.listDepartments("5")).thenThrow(new ServiceException("来源超时"));
        assertThatThrownBy(() -> service.syncChildren(100L)).isInstanceOf(ServiceException.class); verify(lease).close();
        verify(writer,never()).write(any());
        reset(source); doThrow(new ServiceException("无权限")).when(scope).checkDeptDataScope(100L);
        assertThatThrownBy(() -> service.syncChildren(100L)).isInstanceOf(ServiceException.class); verifyNoInteractions(source);
    }

    @Test void writerExceptionReleasesLeaseAndNullResultNeverWrites() {
        when(source.listDepartments("5")).thenReturn(DeptSyncPlannerTest.snapshot());
        when(writer.readState(eq(100L),any())).thenReturn(new DeptSyncLocalState(DeptSyncPlannerTest.company(),List.of(DeptSyncPlannerTest.company()),Set.of()));
        doThrow(new ServiceException("事务失败")).when(writer).write(any());
        assertThatThrownBy(() -> service.syncChildren(100L)).isInstanceOf(ServiceException.class); verify(lease).close();
        reset(writer,lease);
        // 来源约定：外部 null 结果由来源实现抛出 ServiceException。
        when(source.listDepartments("5")).thenThrow(new ServiceException("组织来源查询结果为空"));
        assertThatThrownBy(() -> service.syncChildren(100L)).isInstanceOf(ServiceException.class); verify(lease).close(); verifyNoInteractions(writer);
    }
}
