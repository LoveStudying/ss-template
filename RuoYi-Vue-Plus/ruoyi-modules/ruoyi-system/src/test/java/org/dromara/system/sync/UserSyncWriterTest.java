package org.dromara.system.sync;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.sync.UserSyncPlan;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.domain.vo.UserSyncResultVo;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.impl.UserSyncWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证员工同步写入：行数校验回滚、停用乐观条件与提交后缓存清理。 */
class UserSyncWriterTest {
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final CacheManager caches = mock(CacheManager.class);
    private final Cache nameCache = mock(Cache.class);
    private final Cache nickCache = mock(Cache.class);
    private final UserSyncWriter writer = new UserSyncWriter(users, caches);

    /** lambda 条件依赖实体元数据，单元测试中手动注册。 */
    @BeforeAll static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    @BeforeEach void openTx() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(caches.getCache(CacheNames.SYS_USER_NAME)).thenReturn(nameCache);
        when(caches.getCache(CacheNames.SYS_NICKNAME)).thenReturn(nickCache);
    }

    @AfterEach void closeTx() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private static SysUser update(long id) {
        SysUser row = new SysUser(id);
        row.setDeptId(300L); row.setUserName("u" + id); row.setNickName("张三"); row.setGender("0");
        row.setEmail(""); row.setPhoneNumber(""); row.setHrmUserId("h" + id); row.setHrmStatus("1");
        row.setHrmSyncTime(LocalDateTime.of(2026, 10, 13, 12, 0));
        return row;
    }

    private static UserSyncPlan.Disable disable(long id, String expected) {
        SysUser row = new SysUser(id);
        row.setHrmStatus("5"); row.setStatus(SystemConstants.DISABLE); row.setHrmSyncTime(LocalDateTime.of(2026, 10, 13, 12, 0));
        return new UserSyncPlan.Disable(row, expected);
    }

    private static UserSyncPlan.Disable disable(long id) {
        return disable(id, "1");
    }

    @Test
    void writesAllRowsAndEvictsCachesAfterCommit() {
        SysUser insert = update(0); insert.setUserId(null); insert.setUserName("new");
        when(users.exists(any())).thenReturn(false);
        when(users.insert(any(SysUser.class))).thenReturn(1);
        when(users.update(isNull(), any())).thenReturn(1);
        var plan = new UserSyncPlan(List.of(insert), List.of(update(10)), List.of(disable(11)), Set.of(10L, 11L), new UserSyncResultVo());
        writer.write(plan);
        verify(users).insert(insert);
        verify(users, times(2)).update(isNull(), any());
        verifyNoInteractions(nameCache);
        commit();
        verify(nameCache).evict(10L); verify(nameCache).evict(11L);
        verify(nickCache).evict(10L); verify(nickCache).evict(11L);
    }

    @Test
    void updateCountMismatchRollsBack() {
        when(users.update(isNull(), any())).thenReturn(0);
        var plan = new UserSyncPlan(List.of(), List.of(update(10)), List.of(), Set.of(10L), new UserSyncResultVo());
        assertThatThrownBy(() -> writer.write(plan)).isInstanceOf(ServiceException.class).hasMessageContaining("请刷新后重试");
    }

    @Test
    void disableOnlyWhenStillActive() {
        when(users.update(isNull(), any())).thenReturn(0);
        var plan = new UserSyncPlan(List.of(), List.of(), List.of(disable(11)), Set.of(11L), new UserSyncResultVo());
        assertThatThrownBy(() -> writer.write(plan)).isInstanceOf(ServiceException.class).hasMessageContaining("停用用户失败");
    }

    @Test
    @SuppressWarnings("unchecked")
    void disableGuardUsesPreviousStatus() {
        ArgumentCaptor<LambdaUpdateWrapper<SysUser>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        when(users.update(isNull(), wrapper.capture())).thenReturn(0);
        var plan = new UserSyncPlan(List.of(), List.of(), List.of(disable(11, "A")), Set.of(11L), new UserSyncResultVo());
        assertThatThrownBy(() -> writer.write(plan)).isInstanceOf(ServiceException.class)
            .hasMessage("停用用户失败，请刷新后重试");
        LambdaUpdateWrapper<SysUser> w = wrapper.getValue();
        // WHERE 中 hrm_status 条件绑定的参数必须是读取时的原值，而非固定的在职值
        var matcher = java.util.regex.Pattern.compile("hrm_status = #\\{ew\\.paramNameValuePairs\\.(\\w+)}").matcher(w.getSqlSegment());
        assertThat(matcher.find()).isTrue();
        assertThat(w.getParamNameValuePairs().get(matcher.group(1))).isEqualTo("A");
        assertThat(w.getSqlSegment()).contains("user_id =");
    }

    @Test
    void insertRejectedWhenUserNameAppearedConcurrently() {
        SysUser insert = update(0); insert.setUserId(null);
        when(users.exists(any())).thenReturn(true);
        var plan = new UserSyncPlan(List.of(insert), List.of(), List.of(), Set.of(), new UserSyncResultVo());
        assertThatThrownBy(() -> writer.write(plan)).isInstanceOf(ServiceException.class);
        verify(users, never()).insert(any(SysUser.class));
    }

    @Test
    void cacheFailureAddsWarningButKeepsResult() {
        when(users.update(isNull(), any())).thenReturn(1);
        doThrow(new IllegalStateException("redis down")).when(nameCache).evict(10L);
        var result = new UserSyncResultVo();
        writer.write(new UserSyncPlan(List.of(), List.of(update(10)), List.of(), Set.of(10L), result));
        commit();
        verify(nickCache).evict(10L);
        assertThat(result.getWarnings()).extracting("type").containsExactly("CACHE_REFRESH_FAILED");
    }

    @Test
    void readStateCollectsCandidatesAndScope() {
        SysUser byId = UserSyncPlannerTest.local(10, "a", "h1", "1");
        SysUser byName = UserSyncPlannerTest.local(11, "b", null, null);
        SysUser bound = UserSyncPlannerTest.local(12, "c", "h9", "1");
        when(users.selectList(any())).thenReturn(List.of(byId), List.of(byName), List.of(bound));
        SysUserVo visible = new SysUserVo(); visible.setUserId(10L);
        when(users.selectUserList(any())).thenReturn(List.of(visible));
        var state = writer.readState(300L, List.of(UserSyncPlannerTest.rec("h1", "a", "1"), UserSyncPlannerTest.rec("h2", "b", "1")));
        assertThat(state.byHrmUserId()).containsOnlyKeys("h1");
        assertThat(state.byUserName()).containsOnlyKeys("b");
        assertThat(state.deptBound()).containsExactly(bound);
        assertThat(state.scopedUserIds()).containsExactly(10L);
    }

    @Test
    void readStateWithNoRecordsOnlyQueriesDeptBound() {
        when(users.selectList(any())).thenReturn(List.of());
        var state = writer.readState(300L, List.of());
        verify(users, times(1)).selectList(any());
        verify(users, never()).selectUserList(any());
        assertThat(state.scopedUserIds()).isEmpty();
    }
}
