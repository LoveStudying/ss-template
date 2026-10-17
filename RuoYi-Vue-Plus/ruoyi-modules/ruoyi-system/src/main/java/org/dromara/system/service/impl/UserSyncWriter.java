package org.dromara.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.sync.UserSyncLocalState;
import org.dromara.system.domain.sync.UserSyncPlan;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.domain.vo.UserSyncResultVo;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.dromara.system.constant.UserSyncConstants.CACHE_REFRESH_FAILED;

/**
 * 员工同步的本地读取与事务边界；网络拉取由应用服务在事务外完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSyncWriter {
    private final SysUserMapper userMapper;
    private final CacheManager cacheManager;

    /**
     * 读取匹配候选、同名候选、本部门已绑定用户，以及候选中操作人可见的用户。
     *
     * @param deptId    本地部门 ID
     * @param employees 已去重的外部员工
     * @return 本地状态
     */
    public UserSyncLocalState readState(Long deptId, List<OrgEmployee> employees) {
        List<String> ids = employees.stream().map(OrgEmployee::id).distinct().toList();
        List<String> names = employees.stream().map(OrgEmployee::loginId).filter(Objects::nonNull).distinct().toList();
        List<SysUser> byId = ids.isEmpty() ? List.of()
            : userMapper.selectList(Wrappers.<SysUser>lambdaQuery().in(SysUser::getHrmUserId, ids));
        List<SysUser> byName = names.isEmpty() ? List.of()
            : userMapper.selectList(Wrappers.<SysUser>lambdaQuery().in(SysUser::getUserName, names));
        List<SysUser> bound = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
            .eq(SysUser::getDeptId, deptId).isNotNull(SysUser::getHrmUserId));
        Set<Long> candidates = new LinkedHashSet<>();
        byId.forEach(u -> candidates.add(u.getUserId()));
        byName.forEach(u -> candidates.add(u.getUserId()));
        // selectUserList 带数据权限注解，用它判断哪些候选账号可以修改
        Set<Long> scoped = candidates.isEmpty() ? Set.of()
            : userMapper.selectUserList(Wrappers.<SysUser>lambdaQuery().in(SysUser::getUserId, candidates))
                .stream().map(SysUserVo::getUserId).collect(Collectors.toSet());
        return new UserSyncLocalState(group(byId, SysUser::getHrmUserId),
            group(byName, u -> UserSyncPlanner.nameKey(u.getUserName())), bound, scoped);
    }

    /**
     * 一次性写入计划，任一行数不符即回滚；缓存只在提交后清理。
     *
     * @param plan 同步计划
     * @return 同步结果
     */
    @Transactional(rollbackFor = Exception.class)
    public UserSyncResultVo write(UserSyncPlan plan) {
        Long operator = currentUserId();
        for (SysUser row : plan.inserts()) {
            boolean taken = userMapper.exists(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUserName, row.getUserName()).or().eq(SysUser::getHrmUserId, row.getHrmUserId()));
            if (taken) {
                throw retry("登录名 " + row.getUserName() + " 已被占用");
            }
            if (userMapper.insert(row) != 1) {
                throw retry("新增用户失败");
            }
        }
        for (SysUser row : plan.updates()) {
            int count = userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getDeptId, row.getDeptId())
                .set(SysUser::getUserName, row.getUserName())
                .set(SysUser::getNickName, row.getNickName())
                .set(SysUser::getGender, row.getGender())
                .set(SysUser::getEmail, row.getEmail())
                .set(SysUser::getPhoneNumber, row.getPhoneNumber())
                .set(SysUser::getHrmUserId, row.getHrmUserId())
                .set(SysUser::getHrmWorkcode, row.getHrmWorkcode())
                .set(SysUser::getHrmStatus, row.getHrmStatus())
                .set(SysUser::getHrmDepartmentId, row.getHrmDepartmentId())
                .set(SysUser::getHrmSubcompanyId, row.getHrmSubcompanyId())
                .set(SysUser::getHrmLastChangeDate, row.getHrmLastChangeDate())
                .set(SysUser::getHrmSyncTime, row.getHrmSyncTime())
                .set(SysUser::getUpdateBy, operator)
                .set(SysUser::getUpdateTime, row.getHrmSyncTime())
                .eq(SysUser::getUserId, row.getUserId()));
            if (count != 1) {
                throw retry("更新用户失败");
            }
        }
        for (UserSyncPlan.Disable disable : plan.disables()) {
            SysUser row = disable.row();
            // 带上读取时的 hrm_status 条件，读取后状态已被他人修改时不覆盖
            int count = userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getHrmStatus, row.getHrmStatus())
                .set(SysUser::getHrmSyncTime, row.getHrmSyncTime())
                .set(SysUser::getStatus, row.getStatus())
                .set(SysUser::getUpdateBy, operator)
                .set(SysUser::getUpdateTime, row.getHrmSyncTime())
                .eq(SysUser::getUserId, row.getUserId())
                .eq(SysUser::getHrmStatus, disable.expectedHrmStatus()));
            if (count != 1) {
                throw retry("停用用户失败");
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictAfterCommit(plan);
            }
        });
        return plan.result();
    }

    private void evictAfterCommit(UserSyncPlan plan) {
        boolean failed = false;
        // 单个 key 失败仍继续清理其余缓存
        for (Long id : plan.affectedUserIds()) {
            for (String name : List.of(CacheNames.SYS_USER_NAME, CacheNames.SYS_NICKNAME)) {
                try {
                    Cache cache = cacheManager.getCache(name);
                    if (cache != null) {
                        cache.evict(id);
                    }
                } catch (RuntimeException e) {
                    failed = true;
                    log.error("员工同步已提交，用户缓存清理失败 userId={} cache={} causeType={}", id, name, e.getClass().getSimpleName());
                }
            }
        }
        if (failed) {
            plan.result().warn(CACHE_REFRESH_FAILED, null, null, "数据已提交，部分用户缓存清理失败");
        }
    }

    private static Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, List<SysUser>> group(List<SysUser> users, Function<SysUser, String> key) {
        return users.stream().collect(Collectors.groupingBy(key, Collectors.toCollection(ArrayList::new)));
    }

    private static ServiceException retry(String reason) {
        return new ServiceException(reason + "，请刷新后重试");
    }
}
