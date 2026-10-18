package org.dromara.system.sync;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.hrm.client.HrmClient;
import org.dromara.common.hrm.domain.HrmQueryResult;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.sync.*;
import org.dromara.system.service.impl.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证部门子树同步计划的身份、树关系、停用子树跳过与状态保护规则。 */
class DeptSyncPlannerTest {
    private final DeptSyncPlanner planner=new DeptSyncPlanner();
    private final LocalDateTime time=LocalDateTime.of(2026,10,13,12,0);

    static SysDept company() { return DeptBindingValidatorTest.dept(100,"subcompany","5"); }
    static SysDept child(long id, String businessId, String status) {
        SysDept d=DeptBindingValidatorTest.dept(id,"department",businessId);
        d.setParentId(100L); d.setAncestors("0,100"); d.setHrmSupdepartmentId("0");
        d.setStatus(status); d.setHrmCanceled("0"); return d;
    }
    /** 经来源校验器排序生成快照，与真实来源返回的拓扑顺序一致。 */
    static OrgDepartmentSnapshot snapshot(OrgDepartment... nodes) {
        return new OrgSnapshotValidator().validate("5",Arrays.asList(nodes));
    }
    /** 原始状态为 "1" 时视为停用，其余原样保留。 */
    static OrgDepartment node(String id, String parent, String rawStatus) { return DeptBindingValidatorTest.node(id,parent,rawStatus); }
    private DeptSyncPlan plan(String rawStatus, SysDept existing, Set<Long> users) {
        return planner.plan(snapshot(node("2977","0",rawStatus)),
            new DeptSyncLocalState(company(),List.of(company(),existing),users),time);
    }

    @Test
    void canceledExistingDepartmentIsNotUpdatedEvenWhenItHasUsers() {
        SysDept before=child(101,"2977","0"); var plan=plan("1",before,Set.of(101L));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.updates()).extracting(u -> u.after().getDeptId()).containsExactly(100L);
        assertThat(plan.result().getFetchedCount()).isEqualTo(1);
        assertThat(plan.result().getSelectedCount()).isZero();
        assertThat(plan.result().getUpdatedCount()).isZero();
        assertThat(plan.result().getStatusSkippedCount()).isZero();
        assertThat(plan.result().getWarnings()).isEmpty();
        assertThat(before.getHrmCanceled()).isEqualTo("0");
        assertThat(before.getLastSyncTime()).isNull();
    }

    @Test
    void hasUsersKeepsDisabledStatusWhenSourceEnabled() {
        var p=plan("0",child(101,"2977","1"),Set.of(101L));
        assertThat(p.updates().stream().filter(u -> u.after().getDeptId()==101L).findFirst().orElseThrow().after().getStatus()).isEqualTo("1");
        assertThat(p.result().getStatusSkippedCount()).isEqualTo(1);
        assertThat(p.result().getWarnings().getFirst().getHrmCanceled()).isEqualTo("0");
    }

    @Test
    void userRemovedAllowsFollowingSource() {
        var p=plan("0",child(101,"2977","1"),Set.of());
        assertThat(p.updates().stream().filter(u -> u.after().getDeptId()==101L).findFirst().orElseThrow().after().getStatus()).isEqualTo("0");
        assertThat(p.result().getStatusSkippedCount()).isZero();
    }

    @Test
    void repeatsSnapshotWithoutChangingIdentityAndOnlySyncTimeDoesNotCountAsUpdate() {
        var snap=snapshot(node("1","0","0"));
        var first=planner.plan(snap,new DeptSyncLocalState(company(),List.of(company()),Set.of()),time);
        SysDept created=first.inserts().getFirst();
        var next=planner.plan(snap,new DeptSyncLocalState(company(),List.of(company(),created),Set.of()),time.plusMinutes(1));
        assertThat(next.inserts()).isEmpty(); assertThat(next.result().getUnchangedCount()).isEqualTo(1);
        assertThat(next.result().getUpdatedCount()).isZero();
        assertThat(next.updates().stream().map(u -> u.after().getDeptId())).contains(created.getDeptId());
        SysDept anchor=next.updates().stream().filter(u -> u.after().getDeptId()==100L).findFirst().orElseThrow().after();
        assertThat(anchor.getDeptName()).isEqualTo(company().getDeptName()); assertThat(anchor.getLastSyncTime()).isEqualTo(time.plusMinutes(1));
    }

    @Test
    void canceledExistingParentAndManualChildrenRemainUntouched() {
        SysDept parent=child(101,"2977","0"); SysDept manual=child(102,null,"0");
        manual.setDeptType(null); manual.setParentId(101L); manual.setAncestors("0,100,101");
        var p=planner.plan(snapshot(node("2977","0","1")),
            new DeptSyncLocalState(company(),List.of(company(),parent,manual),Set.of()),time);
        assertThat(p.updates()).extracting(u -> u.after().getDeptId()).containsExactly(100L);
        assertThat(p.result().getWarnings()).isEmpty();
        assertThat(p.result().getSelectedCount()).isZero();
    }

    @Test
    void skipsCanceledSubtreeAndStillImportsEnabledSiblings() {
        var snapshot=snapshot(node("1","0","1"),node("2","1","0"),node("3","2",""),node("4","1","1"),
            node("5","0","0"),node("6","5",""));
        var plan=planner.plan(snapshot,new DeptSyncLocalState(company(),List.of(company()),Set.of()),time);
        assertThat(plan.inserts()).extracting(SysDept::getBusinessId).containsExactly("5","6");
        assertThat(plan.inserts()).allMatch(d -> "0".equals(d.getStatus()));
        assertThat(plan.inserts().get(1).getParentId()).isEqualTo(plan.inserts().getFirst().getDeptId());
        assertThat(plan.inserts().get(1).getHrmCanceled()).isEqualTo("");
        assertThat(plan.result().getFetchedCount()).isEqualTo(6);
        assertThat(plan.result().getSelectedCount()).isEqualTo(2);
        assertThat(plan.result().getCreatedCount()).isEqualTo(2);
    }

    @Test
    void skipsCanceledIdentityEvenWhenLocallyDeleted() {
        SysDept deleted=child(101,"2977","0"); deleted.setDelFlag("1");
        var plan=plan("1",deleted,Set.of());
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.updates()).extracting(u -> u.after().getDeptId()).containsExactly(100L);
    }

    @Test
    void enabledDepartmentStillCannotBeImportedUnderDisabledLocalAnchor() {
        SysDept disabled=company(); disabled.setStatus("1");
        assertThatThrownBy(() -> planner.plan(snapshot(node("1","0","0")),
            new DeptSyncLocalState(disabled,List.of(disabled),Set.of()),time)).isInstanceOf(ServiceException.class);
    }

    @Test
    void movingGlobalIdentityPreservesLocalIdAndUpdatesManualDescendantPaths() {
        SysDept oldCompany=DeptBindingValidatorTest.dept(200,"subcompany","6"); oldCompany.setHrmSubcompanyId("6");
        SysDept before=child(101,"1","0"); before.setParentId(200L); before.setAncestors("0,200"); before.setHrmSubcompanyId("6");
        SysDept manual=child(102,null,"0"); manual.setDeptType(null); manual.setParentId(101L); manual.setAncestors("0,200,101");
        var snap=snapshot(node("1","0","0"));
        var p=planner.plan(snap,new DeptSyncLocalState(company(),List.of(company(),oldCompany,before,manual),Set.of()),time);
        assertThat(p.inserts()).isEmpty(); assertThat(p.requiredSubcompanyIds()).containsExactlyInAnyOrder("5","6");
        SysDept moved=p.updates().stream().filter(u -> u.after().getDeptId()==101L).findFirst().orElseThrow().after();
        assertThat(moved.getParentId()).isEqualTo(100L); assertThat(moved.getHrmSubcompanyId()).isEqualTo("5");
        assertThat(p.updates().stream().filter(u -> u.after().getDeptId()==102L).findFirst().orElseThrow().after().getAncestors()).isEqualTo("0,100,101");
        manual.setDeptType("department"); manual.setBusinessId("9"); manual.setHrmSubcompanyId("6");
        assertThatThrownBy(() -> planner.plan(snap,new DeptSyncLocalState(company(),List.of(company(),oldCompany,before,manual),Set.of()),time))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void deletedIdentityAndSiblingNamesConflictWithoutClaimingByName() {
        SysDept before=child(101,"2977","0"); before.setDelFlag("1");
        assertThatThrownBy(() -> plan("0",before,Set.of())).isInstanceOf(ServiceException.class);
        before.setDelFlag("0"); before.setBusinessId("999"); before.setDeptName("部门2977");
        assertThatThrownBy(() -> plan("0",before,Set.of())).isInstanceOf(ServiceException.class);
    }

    @Test
    void ordinarySubtreeLeavesAnchorAndSiblingsAndMissingNodesUntouched() {
        SysDept anchor=child(101,"1","0"); SysDept sibling=child(102,"3","0"); SysDept missing=child(103,"4","0");
        var snap=snapshot(node("1","0","0"),node("2","1","0"),node("3","0","1"));
        var p=planner.plan(snap,new DeptSyncLocalState(anchor,List.of(company(),anchor,sibling,missing),Set.of()),time);
        assertThat(p.result().getSelectedCount()).isEqualTo(1); assertThat(p.inserts().getFirst().getParentId()).isEqualTo(101L);
        assertThat(p.updates()).hasSize(1); assertThat(p.updates().getFirst().after().getStatus()).isEqualTo("0");
        assertThat(p.updates().getFirst().after().getDeptName()).isEqualTo(anchor.getDeptName());
    }

    @Test
    void canceledDepartmentAnchorSkipsItsEntireSubtree() {
        SysDept anchor=child(101,"1","0");
        var snapshot=snapshot(node("1","0","1"),node("2","1","0"));
        var plan=planner.plan(snapshot,new DeptSyncLocalState(anchor,List.of(company(),anchor),Set.of()),time);
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.result().getSelectedCount()).isZero();
        assertThat(plan.updates()).extracting(u -> u.after().getDeptId()).containsExactly(101L);
        assertThat(plan.updates().getFirst().after().getHrmCanceled()).isEqualTo("0");
    }

    @Test
    void warningTotalsAreNotTruncated() {
        List<SysDept> local=new ArrayList<>(); local.add(company()); Set<Long> users=new HashSet<>();
        List<OrgDepartment> nodes=new ArrayList<>();
        for (int i=1;i<=25;i++) { local.add(child(100+i,""+i,"1")); users.add(100L+i); nodes.add(node(""+i,"0","0")); }
        var p=planner.plan(snapshot(nodes.toArray(OrgDepartment[]::new)),new DeptSyncLocalState(company(),local,users),time);
        assertThat(p.result().getWarningCount()).isEqualTo(25); assertThat(p.result().getWarnings()).hasSize(20);
        assertThat(p.result().isWarningsTruncated()).isTrue(); assertThat(p.result().getStatusSkippedCount()).isEqualTo(25);
    }

    @Test
    void fullAttachmentSkipsCanceledDepartmentsAndSubtreesWithoutWrites() throws Exception {
        HrmClient client=mock(HrmClient.class);
        when(client.getHrmDepartmentInfo("5"))
            .thenReturn(new HrmQueryResult("getHrmDepartmentInfo",false,HrmOrgSyncSourceTest.load("/hrm/departments-subcompany-5.json")));
        var snapshot=new HrmOrgSyncSource(client,new OrgSnapshotValidator()).listDepartments("5");
        var full=planner.plan(snapshot,new DeptSyncLocalState(company(),List.of(company()),Set.of()),time);
        assertThat(full.result().getFetchedCount()).isEqualTo(983);
        assertThat(full.inserts()).hasSize(201); assertThat(full.result().getSelectedCount()).isEqualTo(201);
        assertThat(full.inserts()).noneMatch(d -> "1".equals(d.getHrmCanceled()));
        List<SysDept> local=new ArrayList<>(full.inserts()); local.add(company());
        SysDept anchor=local.stream().filter(d -> "2976".equals(d.getBusinessId()) && "department".equals(d.getDeptType())).findFirst().orElseThrow();
        var subtree=planner.plan(snapshot,new DeptSyncLocalState(anchor,local,Set.of()),time.plusMinutes(1));
        assertThat(subtree.result().getSelectedCount()).isEqualTo(16); assertThat(subtree.inserts()).isEmpty();
        assertThat(subtree.result().getUnchangedCount()).isEqualTo(16);
        assertThat(subtree.updates()).hasSize(17);
    }
}
