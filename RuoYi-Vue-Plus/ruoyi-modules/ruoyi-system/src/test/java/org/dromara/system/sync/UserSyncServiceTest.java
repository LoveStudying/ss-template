package org.dromara.system.sync;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.sync.UserSyncLocalState;
import org.dromara.system.domain.sync.UserSyncPlan;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.service.ISysConfigService;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.OrgSyncSource;
import org.dromara.system.service.impl.OrgSyncSourceProvider;
import org.dromara.system.service.impl.UserSyncPlanner;
import org.dromara.system.service.impl.UserSyncServiceImpl;
import org.dromara.system.service.impl.UserSyncWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 验证员工同步编排：来源前置检查、校验顺序、部门锁、去重、按实际部门匹配与写入异常处理。 */
class UserSyncServiceTest {
    private final SysDeptMapper depts = mock(SysDeptMapper.class);
    private final ISysDeptService deptService = mock(ISysDeptService.class);
    private final ISysConfigService config = mock(ISysConfigService.class);
    private final OrgSyncSourceProvider provider = mock(OrgSyncSourceProvider.class);
    private final OrgSyncSource source = mock(OrgSyncSource.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final UserSyncWriter writer = mock(UserSyncWriter.class);
    private final UserSyncServiceImpl service = new UserSyncServiceImpl(depts, deptService, config, provider, redisson,
        new UserSyncPlanner(), writer);

    /** lambda 条件依赖实体元数据，单元测试中手动注册。 */
    @BeforeAll static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysDept.class);
    }

    static SysDept dept(String type, String businessId) {
        SysDept d = new SysDept();
        d.setDeptId(300L); d.setDeptName("IT外部支持人员"); d.setDeptType(type); d.setBusinessId(businessId);
        return d;
    }

    private static SysDeptVo targetDept(long id, String businessId, String status) {
        SysDeptVo d = new SysDeptVo();
        d.setDeptId(id); d.setBusinessId(businessId); d.setDeptType("department"); d.setStatus(status);
        return d;
    }

    /** 与 dev-bench 样例 users-department-1885.json 等价的四名员工：三名离职无登录名，一名在职。 */
    static List<OrgEmployee> sample() {
        LocalDate old = LocalDate.of(2023, 2, 17);
        return List.of(
            UserSyncPlannerTest.emp("529155", null, null, "刘嘉伟", "0", "liujiawei@3sbio.com", "17721312518", "5", "1885", "48", old),
            UserSyncPlannerTest.emp("529349", null, null, "卫梦杰", "1", null, "19534077313", "5", "1885", "48", old),
            UserSyncPlannerTest.emp("529480", null, null, "韩夏松", "0", "hanxiasong@3sbio.com", "18721847602", "5", "1885", "48", old),
            UserSyncPlannerTest.emp("538233", "910052", "yangying01", "杨莹", "1", "ying.yang06@going-link.com", "15946929549", "1", "1885", "48", LocalDate.of(2025, 9, 1)));
    }

    private void returnedEmployee(String departmentId, String status) {
        when(source.listEmployees(anyString())).thenReturn(List.of(
            UserSyncPlannerTest.emp("child-user", null, "child-login", "测试员工", "2", null, null, status, departmentId, null, null)));
    }

    private UserSyncPlan syncPlan() {
        service.syncDepartment(300L);
        ArgumentCaptor<UserSyncPlan> plan = ArgumentCaptor.forClass(UserSyncPlan.class);
        verify(writer).write(plan.capture());
        return plan.getValue();
    }

    @BeforeEach void setup() {
        when(provider.require()).thenReturn(source);
        when(depts.selectById(300L)).thenReturn(dept("department", "1885"));
        when(depts.selectDeptList(any())).thenReturn(List.of(targetDept(300L, "1885", "0")));
        when(config.selectConfigByKey("sys.user.initPassword")).thenReturn("123456");
        when(redisson.getLock("system:user:sync:300")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(source.listEmployees(anyString())).thenReturn(sample());
        when(source.isActiveStatus(any())).thenAnswer(call -> "1".equals(call.getArgument(0)));
        when(writer.readState(eq(300L), any())).thenReturn(new UserSyncLocalState(Map.of(), Map.of(), List.of(), Set.of()));
        when(writer.write(any())).thenAnswer(call -> ((UserSyncPlan) call.getArgument(0)).result());
    }

    @Test
    void syncsSampleWithDepartmentIdAndReleasesLock() {
        when(depts.selectById(300L)).thenReturn(dept("department", " 1885 "));
        var result = service.syncDepartment(300L);
        verify(source).listEmployees("1885");
        assertThat(result.getFetched()).isEqualTo(4);
        assertThat(result.getAdded()).isEqualTo(1);
        assertThat(result.getSourceDepartmentId()).isEqualTo("1885");
        assertThat(result.getDeptId()).isEqualTo(300L);
        ArgumentCaptor<UserSyncPlan> plan = ArgumentCaptor.forClass(UserSyncPlan.class);
        verify(writer).write(plan.capture());
        assertThat(plan.getValue().inserts().get(0).getPassword()).startsWith("$2a$").isNotEqualTo("123456");
        verify(lock).unlock();
    }

    @Test
    void failsFastWhenSourceDisabled() {
        when(provider.require()).thenThrow(new ServiceException("未启用组织信息来源，无法同步"));
        assertThatThrownBy(() -> service.syncDepartment(300L)).isInstanceOf(ServiceException.class)
            .hasMessage("未启用组织信息来源，无法同步");
        verifyNoInteractions(depts, deptService, config, redisson, source, writer);
    }

    @Test
    void validationOrder() {
        when(depts.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.syncDepartment(1L)).hasMessage("部门不存在");
        verify(deptService, never()).checkDeptDataScope(any());

        // 数据权限先于类型校验
        when(depts.selectById(300L)).thenReturn(dept("subcompany", "48"));
        doThrow(new ServiceException("没有权限访问部门数据！")).when(deptService).checkDeptDataScope(300L);
        assertThatThrownBy(() -> service.syncDepartment(300L)).hasMessage("没有权限访问部门数据！");
        doNothing().when(deptService).checkDeptDataScope(300L);

        // 类型先于外部 ID 校验
        when(depts.selectById(300L)).thenReturn(dept("subcompany", null));
        assertThatThrownBy(() -> service.syncDepartment(300L)).hasMessage("请选择普通部门进行员工同步");

        // 外部 ID 先于初始密码校验
        when(config.selectConfigByKey("sys.user.initPassword")).thenReturn(null);
        when(depts.selectById(300L)).thenReturn(dept("department", " "));
        assertThatThrownBy(() -> service.syncDepartment(300L)).hasMessage("该部门未绑定外部部门 ID，无法同步员工");
        verify(config, never()).selectConfigByKey(any());

        verifyNoInteractions(redisson, source, writer);
        InOrder order = inOrder(provider, depts, deptService);
        order.verify(provider).require();
        order.verify(depts).selectById(1L);
    }

    @Test
    void missingInitPasswordFails() {
        when(config.selectConfigByKey("sys.user.initPassword")).thenReturn("");
        assertThatThrownBy(() -> service.syncDepartment(300L)).hasMessage("未配置用户初始密码参数 sys.user.initPassword");
        verifyNoInteractions(source, redisson);
    }

    @Test
    void lockBusyFails() {
        when(lock.tryLock()).thenReturn(false);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        assertThatThrownBy(() -> service.syncDepartment(300L)).hasMessage("该部门正在同步，请稍后重试");
        verifyNoInteractions(source);
        verify(lock, never()).unlock();
    }

    @Test
    void sourceFailureReleasesLockWithoutWrite() {
        when(source.listEmployees(anyString())).thenThrow(new ServiceException("获取员工信息调用超时"));
        assertThatThrownBy(() -> service.syncDepartment(300L)).isInstanceOf(ServiceException.class).hasMessage("获取员工信息调用超时");
        verify(writer, never()).write(any());
        verify(lock).unlock();
    }

    @Test
    void unexpectedWriteFailureBecomesGenericMessage() {
        doThrow(new IllegalStateException("db")).when(writer).write(any());
        assertThatThrownBy(() -> service.syncDepartment(300L)).hasMessage("员工同步写入失败，请重试");
        verify(lock).unlock();
    }

    @Test
    void skipsBlankOrDuplicateEmployeeId() {
        List<OrgEmployee> input = new ArrayList<>();
        input.add(UserSyncPlannerTest.emp(null, null, "a", "张三", "0", null, null, "1", "1885", "48", null));
        input.add(UserSyncPlannerTest.emp("1", null, "a", "张三", "0", null, null, "1", "1885", "48", null));
        input.add(UserSyncPlannerTest.emp("1", null, "b", "张三", "0", null, null, "1", "1885", "48", null));
        when(source.listEmployees(anyString())).thenReturn(input);
        UserSyncPlan plan = syncPlan();
        assertThat(plan.result().getFetched()).isEqualTo(1);
        assertThat(plan.inserts()).extracting(u -> u.getUserName()).containsExactly("a");
        assertThat(plan.result().getSkipped()).isEqualTo(2);
        assertThat(plan.result().getWarnings()).extracting("reason").containsExactly("外部人员 ID 为空", "外部来源返回重复人员 ID");
        assertThat(plan.result().getWarnings()).extracting("hrmUserId").containsExactly(null, "1");
    }

    @Test
    void localActiveStatusIsInterpretedBySource() {
        returnedEmployee("1885", "5");
        var user = UserSyncPlannerTest.local(10L, "child-login", "child-user", "1");
        when(writer.readState(eq(300L), any())).thenReturn(UserSyncPlannerTest.state(List.of(user), List.of()));
        when(source.isActiveStatus(any())).thenReturn(false);
        UserSyncPlan plan = syncPlan();
        assertThat(plan.disables()).isEmpty();
        verify(source).isActiveStatus("1");
    }

    @Test
    void employeeOfMissingDeletedOrInaccessibleDepartmentIsSkipped() {
        returnedEmployee("1886", "1");
        when(depts.selectDeptList(any())).thenReturn(List.of());
        UserSyncPlan plan = syncPlan();
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.disables()).isEmpty();
        assertThat(plan.result().getSkipped()).isEqualTo(1);
        assertThat(plan.result().getWarnings()).singleElement().satisfies(w -> {
            assertThat(w.getType()).isEqualTo("SKIPPED");
            assertThat(w.getHrmUserId()).isEqualTo("child-user");
            assertThat(w.getReason()).contains("部门", "不存在", "已删除");
        });
    }

    @Test
    void employeeIsInsertedIntoActualDepartmentInsteadOfSelectedParent() {
        returnedEmployee("1886", "1");
        when(depts.selectDeptList(any())).thenReturn(List.of(targetDept(301L, "1886", "0")));
        UserSyncPlan plan = syncPlan();
        assertThat(plan.inserts()).singleElement().satisfies(u -> {
            assertThat(u.getDeptId()).isEqualTo(301L);
            assertThat(u.getHrmDepartmentId()).isEqualTo("1886");
        });
        assertThat(plan.result().getAdded()).isEqualTo(1);
    }

    @Test
    void disabledDepartmentStillAcceptsEmployeeWithoutBeingEnabled() {
        returnedEmployee("1886", "1");
        SysDeptVo disabled = targetDept(301L, "1886", "1");
        when(depts.selectDeptList(any())).thenReturn(List.of(disabled));
        UserSyncPlan plan = syncPlan();
        assertThat(plan.inserts()).singleElement().satisfies(u -> {
            assertThat(u.getDeptId()).isEqualTo(301L);
            assertThat(u.getStatus()).isEqualTo("0");
        });
        assertThat(disabled.getStatus()).isEqualTo("1");
        assertThat(plan.result().getSkipped()).isZero();
    }

    @Test
    void existingEmployeeIsMovedToActualDisabledDepartment() {
        returnedEmployee("1886", "1");
        when(depts.selectDeptList(any())).thenReturn(List.of(targetDept(301L, "1886", "1")));
        var user = UserSyncPlannerTest.local(10L, "child-login", "child-user", "1");
        when(writer.readState(eq(300L), any())).thenReturn(UserSyncPlannerTest.state(List.of(user), List.of()));
        UserSyncPlan plan = syncPlan();
        assertThat(plan.updates()).singleElement().satisfies(u -> {
            assertThat(u.getUserId()).isEqualTo(10L);
            assertThat(u.getDeptId()).isEqualTo(301L);
            assertThat(u.getStatus()).isNull();
        });
        assertThat(plan.inserts()).isEmpty();
    }

    @Test
    void missingDepartmentSkipsInactiveEmployeeWithoutDisablingAccount() {
        returnedEmployee("1886", "5");
        when(depts.selectDeptList(any())).thenReturn(List.of());
        var user = UserSyncPlannerTest.local(10L, "child-login", "child-user", "1");
        when(writer.readState(eq(300L), any())).thenReturn(UserSyncPlannerTest.state(List.of(user), List.of()));
        UserSyncPlan plan = syncPlan();
        assertThat(plan.disables()).isEmpty();
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.result().getSkipped()).isEqualTo(1);
    }

    @Test
    void blankEmployeeDepartmentDoesNotFallBackToSelectedDepartment() {
        returnedEmployee(null, "1");
        UserSyncPlan plan = syncPlan();
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.result().getSkipped()).isEqualTo(1);
        assertThat(plan.result().getWarnings()).singleElement()
            .satisfies(w -> assertThat(w.getReason()).contains("部门", "为空"));
    }

    @Test
    void ambiguousDepartmentMappingIsSkipped() {
        returnedEmployee("1886", "1");
        when(depts.selectDeptList(any())).thenReturn(List.of(
            targetDept(301L, "1886", "0"), targetDept(302L, "1886", "1")));
        UserSyncPlan plan = syncPlan();
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.result().getSkipped()).isEqualTo(1);
        assertThat(plan.result().getWarnings()).singleElement()
            .satisfies(w -> assertThat(w.getReason()).contains("多个", "部门"));
    }

    @Test
    void departmentLookupExcludesDeletedAndSubcompaniesButKeepsDisabledDepartments() {
        returnedEmployee("1886", "1");
        when(depts.selectDeptList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<SysDept> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("business_id IN", "dept_type =", "del_flag =")
                .doesNotContain("status");
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder("1886", "department", "0");
            return List.of(targetDept(301L, "1886", "1"));
        });
        UserSyncPlan plan = syncPlan();
        assertThat(plan.inserts()).singleElement().satisfies(u -> assertThat(u.getDeptId()).isEqualTo(301L));
    }
}
