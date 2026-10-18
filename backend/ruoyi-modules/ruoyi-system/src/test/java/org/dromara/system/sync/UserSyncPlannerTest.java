package org.dromara.system.sync;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.sync.UserSyncLocalState;
import org.dromara.system.domain.sync.UserSyncPlan;
import org.dromara.system.domain.vo.UserSyncResultVo;
import org.dromara.system.service.impl.UserSyncPlanner;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证员工同步计划计算：新增、更新、绑定、停用与跳过规则。 */
class UserSyncPlannerTest {
    static final Long DEPT = 300L;
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);
    private final UserSyncPlanner planner = new UserSyncPlanner();

    /** 按 HRM 约定构造员工：status "1" 在职，空白状态 rawStatus 为 null。 */
    static OrgEmployee emp(String id, String workcode, String loginId, String name, String gender, String email,
                           String mobile, String status, String departmentId, String subcompanyId, LocalDate lastChangeDate) {
        return new OrgEmployee(id, workcode, loginId, name, gender, email, mobile, "1".equals(status), status,
            departmentId, subcompanyId, lastChangeDate);
    }

    static OrgEmployee rec(String id, String loginId, String status) {
        return emp(id, "W" + id, loginId, "张三", "1", "a@b.com", "13800000000", status, "1885", "48", LocalDate.of(2025, 9, 1));
    }

    static SysUser local(long userId, String userName, String hrmUserId, String hrmStatus) {
        SysUser u = new SysUser(userId);
        u.setUserName(userName); u.setNickName("旧名"); u.setDeptId(100L);
        u.setHrmUserId(hrmUserId); u.setHrmStatus(hrmStatus); u.setStatus(SystemConstants.NORMAL);
        return u;
    }

    static UserSyncLocalState state(List<SysUser> users, List<SysUser> deptBound) {
        Map<String, List<SysUser>> byId = users.stream().filter(u -> u.getHrmUserId() != null).collect(Collectors.groupingBy(SysUser::getHrmUserId));
        Map<String, List<SysUser>> byName = users.stream().collect(Collectors.groupingBy(u -> UserSyncPlanner.nameKey(u.getUserName())));
        Set<Long> scoped = users.stream().map(SysUser::getUserId).collect(Collectors.toSet());
        return new UserSyncLocalState(byId, byName, deptBound, scoped);
    }

    private UserSyncPlan run(List<OrgEmployee> records, UserSyncLocalState state) {
        return planner.plan(Map.of("1885", List.of(DEPT)), records, state, "{bcrypt}", NOW, new UserSyncResultVo(), "1"::equals);
    }

    @Test
    void activeMatchedUserIsUpdatedAndMovedButStatusUntouched() {
        SysUser u = local(10, "yangying01", "538233", "5");
        u.setStatus(SystemConstants.DISABLE);
        UserSyncPlan plan = run(List.of(rec("538233", "yangying01", "1")), state(List.of(u), List.of()));
        assertThat(plan.updates()).hasSize(1);
        SysUser row = plan.updates().get(0);
        assertThat(row.getUserId()).isEqualTo(10L);
        assertThat(row.getDeptId()).isEqualTo(DEPT);
        assertThat(row.getNickName()).isEqualTo("张三");
        assertThat(row.getGender()).isEqualTo("1");
        assertThat(row.getHrmStatus()).isEqualTo("1");
        assertThat(row.getHrmSyncTime()).isEqualTo(NOW);
        assertThat(row.getStatus()).isNull();
        assertThat(row.getPassword()).isNull();
        assertThat(plan.result().getUpdated()).isEqualTo(1);
    }

    @Test
    void sameNameUnboundAccountIsBound() {
        SysUser u = local(11, "zhangsan", null, null);
        UserSyncPlan plan = run(List.of(rec("1", "zhangsan", "1")), state(List.of(u), List.of()));
        assertThat(plan.updates()).extracting(SysUser::getUserId).containsExactly(11L);
        assertThat(plan.updates().get(0).getHrmUserId()).isEqualTo("1");
        assertThat(plan.result().getWarnings()).extracting("type").containsExactly("BOUND");
    }

    @Test
    void sameNameBoundToOtherHrmUserIsSkipped() {
        SysUser u = local(12, "zhangsan", "999", "1");
        UserSyncPlan plan = run(List.of(rec("1", "zhangsan", "1")), state(List.of(u), List.of()));
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.result().getSkipped()).isEqualTo(1);
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("登录名已被其他外部人员占用");
    }

    @Test
    void newActiveUserIsInsertedWithInitPassword() {
        UserSyncPlan plan = run(List.of(rec("2", "lisi", "1")), state(List.of(), List.of()));
        assertThat(plan.inserts()).hasSize(1);
        SysUser row = plan.inserts().get(0);
        assertThat(row.getUserId()).isNull();
        assertThat(row.getUserName()).isEqualTo("lisi");
        assertThat(row.getPassword()).isEqualTo("{bcrypt}");
        assertThat(row.getStatus()).isEqualTo(SystemConstants.NORMAL);
        assertThat(row.getUserType()).isEqualTo("sys_user");
        assertThat(row.getDeptId()).isEqualTo(DEPT);
        assertThat(plan.result().getAdded()).isEqualTo(1);
    }

    @Test
    void blankLoginIdIsSkipped() {
        UserSyncPlan plan = run(List.of(rec("3", null, "1")), state(List.of(), List.of()));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("外部登录名为空");
    }

    @Test
    void leavingActiveUserIsOnlyDisabled() {
        SysUser u = local(13, "wangwu", "4", "1");
        UserSyncPlan plan = run(List.of(rec("4", "wangwu", "5")), state(List.of(u), List.of()));
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.disables()).hasSize(1);
        assertThat(plan.disables().get(0).expectedHrmStatus()).isEqualTo("1");
        SysUser row = plan.disables().get(0).row();
        assertThat(row.getUserId()).isEqualTo(13L);
        assertThat(row.getHrmStatus()).isEqualTo("5");
        assertThat(row.getStatus()).isEqualTo(SystemConstants.DISABLE);
        assertThat(row.getHrmSyncTime()).isEqualTo(NOW);
        assertThat(row.getNickName()).isNull();
        assertThat(row.getDeptId()).isNull();
        assertThat(plan.result().getDisabled()).isEqualTo(1);
    }

    @Test
    void inactiveRecordsWithoutActiveLocalAreIgnored() {
        SysUser u = local(14, "zhaoliu", "5", "5");
        UserSyncPlan plan = run(List.of(rec("5", "zhaoliu", "5"), rec("6", "nobody", "4")), state(List.of(u), List.of()));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.disables()).isEmpty();
        assertThat(plan.result().getWarnings()).isEmpty();
    }

    @Test
    void boundUsersNotReturnedAreReported() {
        SysUser gone = local(15, "gone", "7", "1");
        gone.setNickName("已调走");
        UserSyncPlan plan = run(List.of(), state(List.of(gone), List.of(gone)));
        assertThat(plan.result().getWarnings()).singleElement()
            .satisfies(w -> { assertThat(w.getType()).isEqualTo("NOT_RETURNED"); assertThat(w.getName()).isEqualTo("已调走"); });
        assertThat(plan.disables()).isEmpty();
    }

    @Test
    void fieldValidation() {
        OrgEmployee longEmail = emp("8", "W8", "u8", "张三", "2", "x".repeat(51), "1380000", "1", "1885", "48", null);
        OrgEmployee longLogin = emp("9", "W9", "l".repeat(31), "张三", "0", null, null, "1", "1885", "48", null);
        OrgEmployee longName = emp("10", "W10", "u10", "名".repeat(31), "0", null, null, "1", "1885", "48", null);
        UserSyncPlan plan = run(List.of(longEmail, longLogin, longName), state(List.of(), List.of()));
        assertThat(plan.inserts()).singleElement().satisfies(row -> {
            assertThat(row.getEmail()).isEmpty();
            assertThat(row.getPhoneNumber()).isEmpty();
            assertThat(row.getGender()).isEqualTo("2");
        });
        assertThat(plan.result().getWarnings()).extracting("type").containsExactly("FIELD_CLEARED", "FIELD_CLEARED", "SKIPPED", "SKIPPED");
        assertThat(plan.result().getSkipped()).isEqualTo(2);
    }

    @Test
    void superAdminIsNeverTouched() {
        SysUser admin = local(SystemConstants.SUPER_ADMIN_USER_ID, "admin", "11", "1");
        UserSyncPlan plan = run(List.of(rec("11", "admin", "5")), state(List.of(admin), List.of()));
        assertThat(plan.disables()).isEmpty();
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("超级管理员账号不参与同步");
    }

    @Test
    void skipsUserOutsideDataScope() {
        SysUser u = local(16, "outside", "12", "1");
        var s = state(List.of(u), List.of());
        var noScope = new UserSyncLocalState(s.byHrmUserId(), s.byUserName(), s.deptBound(), Set.of());
        UserSyncPlan plan = run(List.of(rec("12", "outside", "1")), noScope);
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("该账号超出当前数据权限");
    }

    @Test
    void duplicateLoginIdInBatchOnlyFirstWins() {
        UserSyncPlan plan = run(List.of(rec("20", "same", "1"), rec("21", "same", "1")), state(List.of(), List.of()));
        assertThat(plan.inserts()).extracting(SysUser::getHrmUserId).containsExactly("20");
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("同批人员登录名重复");
    }

    @Test
    void renamedLoginIdTakenByOtherAccountIsSkipped() {
        SysUser me = local(17, "old", "30", "1");
        SysUser other = local(18, "new", null, null);
        UserSyncPlan plan = run(List.of(rec("30", "new", "1")), state(List.of(me, other), List.of()));
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("登录名已被其他账号占用");
    }

    @Test
    void multipleLocalAccountsBoundToSameHrmUserAreSkipped() {
        UserSyncPlan plan = run(List.of(rec("40", "dup", "1")),
            state(List.of(local(19, "dup1", "40", "1"), local(20, "dup2", "40", "1")), List.of()));
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("本地存在多个绑定该外部人员的账号");
    }

    @Test
    void sameNameMatchIgnoresCaseLikeDatabaseCollation() {
        SysUser u = local(21, "Zhang", null, null);
        UserSyncPlan plan = run(List.of(rec("50", "zhang", "1")), state(List.of(u), List.of()));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.updates()).extracting(SysUser::getUserId).containsExactly(21L);
    }

    @Test
    void caseVariantLoginIdsInBatchOnlyFirstWins() {
        UserSyncPlan plan = run(List.of(rec("51", "Li", "1"), rec("52", "li", "1")), state(List.of(), List.of()));
        assertThat(plan.inserts()).extracting(SysUser::getHrmUserId).containsExactly("51");
        assertThat(plan.result().getWarnings().get(0).getReason()).isEqualTo("同批人员登录名重复");
    }

    @Test
    void blankHrmStatusIsSkippedNotDisabled() {
        SysUser u = local(22, "active", "53", "1");
        UserSyncPlan plan = run(List.of(rec("53", "active", null)), state(List.of(u), List.of()));
        assertThat(plan.disables()).isEmpty();
        assertThat(plan.result().getWarnings()).singleElement()
            .satisfies(w -> assertThat(w.getReason()).isEqualTo("外部人员状态为空"));
    }

    @Test
    void blankStatusSkipsInsteadOfDisabling() {
        SysUser u = local(23, "keep", "54", "1");
        OrgEmployee blank = rec("54", "keep", null);
        assertThat(blank.rawStatus()).isNull();
        assertThat(blank.active()).isFalse();
        UserSyncPlan plan = run(List.of(blank), state(List.of(u), List.of()));
        assertThat(plan.disables()).isEmpty();
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.result().getDisabled()).isZero();
        assertThat(plan.result().getSkipped()).isEqualTo(1);
    }

    @Test
    void matchesLoginIdCaseInsensitively() {
        SysUser u = local(24, "zhangsan", null, null);
        UserSyncPlan plan = run(List.of(rec("55", "ZhangSan", "1")), state(List.of(u), List.of()));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.updates()).singleElement().satisfies(row -> {
            assertThat(row.getUserId()).isEqualTo(24L);
            assertThat(row.getHrmUserId()).isEqualTo("55");
        });
        assertThat(plan.result().getWarnings()).extracting("type").containsExactly("BOUND");
        assertThat(plan.result().getAdded()).isZero();
    }
}
