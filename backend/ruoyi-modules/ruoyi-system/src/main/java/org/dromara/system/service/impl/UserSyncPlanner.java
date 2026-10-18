package org.dromara.system.service.impl;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.sync.UserSyncLocalState;
import org.dromara.system.domain.sync.UserSyncPlan;
import org.dromara.system.domain.vo.UserSyncResultVo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.dromara.system.constant.UserSyncConstants.BOUND;
import static org.dromara.system.constant.UserSyncConstants.FIELD_CLEARED;
import static org.dromara.system.constant.UserSyncConstants.NOT_RETURNED;

/**
 * 计算员工同步的新增、更新、停用和跳过；不访问网络和数据库。
 */
@Component
public class UserSyncPlanner {

    /**
     * 按员工所属外部部门匹配可写入的本地部门并计算同步计划，结果计数和提示写入 result。
     *
     * @param departments     外部部门 ID 到本地普通部门 ID 的映射（已过滤删除和数据权限，停用部门保留）
     * @param employees       已去除空 ID 和重复 ID 的员工
     * @param local           同步前读取的本地用户状态
     * @param encodedPassword 新用户的加密初始密码
     * @param now             同步时间
     * @param result          同步结果
     * @param isActiveStatus  解释本地已存原始状态（hrm_status）是否在职，由来源提供
     * @return 同步计划
     */
    public UserSyncPlan plan(Map<String, List<Long>> departments, List<OrgEmployee> employees, UserSyncLocalState local,
                             String encodedPassword, LocalDateTime now, UserSyncResultVo result,
                             Predicate<String> isActiveStatus) {
        List<SysUser> inserts = new ArrayList<>();
        List<SysUser> updates = new ArrayList<>();
        List<UserSyncPlan.Disable> disables = new ArrayList<>();
        Set<Long> affected = new LinkedHashSet<>();
        Set<String> returned = new HashSet<>();
        // 同批已占用的登录名，防止两条外部记录争用同一账号
        Set<String> claimedNames = new HashSet<>();
        for (OrgEmployee r : employees) {
            returned.add(r.id());
            if (r.rawStatus() == null) {
                result.skip(r.id(), r.name(), "外部人员状态为空");
                continue;
            }
            if (r.departmentId() == null) {
                result.skip(r.id(), r.name(), "外部所属部门 ID 为空");
                continue;
            }
            List<Long> matchedDepartments = departments.getOrDefault(r.departmentId(), List.of());
            if (matchedDepartments.isEmpty()) {
                result.skip(r.id(), r.name(), "外部所属部门在本系统中不存在、已删除或超出当前数据权限");
                continue;
            }
            if (matchedDepartments.size() > 1) {
                result.skip(r.id(), r.name(), "本地存在多个绑定该外部部门的普通部门");
                continue;
            }
            List<SysUser> matched = local.byHrmUserId().getOrDefault(r.id(), List.of());
            if (matched.size() > 1) {
                result.skip(r.id(), r.name(), "本地存在多个绑定该外部人员的账号");
                continue;
            }
            SysUser current = matched.isEmpty() ? null : matched.get(0);
            if (!r.active()) {
                if (current != null && isActiveStatus.test(current.getHrmStatus()) && allowed(current, r, local, result)) {
                    SysUser row = new SysUser(current.getUserId());
                    row.setHrmStatus(r.rawStatus());
                    row.setHrmSyncTime(now);
                    row.setStatus(SystemConstants.DISABLE);
                    disables.add(new UserSyncPlan.Disable(row, current.getHrmStatus()));
                    affected.add(current.getUserId());
                    result.countDisabled();
                }
                continue;
            }
            if (r.loginId() == null) {
                result.skip(r.id(), r.name(), "外部登录名为空");
                continue;
            }
            if (r.loginId().length() > 30) {
                result.skip(r.id(), r.name(), "外部登录名超过 30 位");
                continue;
            }
            if (r.name() == null || r.name().length() > 30) {
                result.skip(r.id(), r.name(), "外部姓名为空或超过 30 位");
                continue;
            }
            List<SysUser> sameName = local.byUserName().getOrDefault(nameKey(r.loginId()), List.of());
            boolean bound = false;
            if (current == null) {
                if (sameName.size() > 1) {
                    result.skip(r.id(), r.name(), "本地存在多个同名账号");
                    continue;
                }
                if (sameName.size() == 1) {
                    if (sameName.get(0).getHrmUserId() != null) {
                        result.skip(r.id(), r.name(), "登录名已被其他外部人员占用");
                        continue;
                    }
                    current = sameName.get(0);
                    bound = true;
                }
            } else {
                Long selfId = current.getUserId();
                if (sameName.stream().anyMatch(u -> !u.getUserId().equals(selfId))) {
                    result.skip(r.id(), r.name(), "登录名已被其他账号占用");
                    continue;
                }
            }
            if (!claimedNames.add(nameKey(r.loginId()))) {
                result.skip(r.id(), r.name(), "同批人员登录名重复");
                continue;
            }
            if (current != null && !allowed(current, r, local, result)) {
                continue;
            }
            SysUser row = current == null ? new SysUser() : new SysUser(current.getUserId());
            fill(row, r, matchedDepartments.getFirst(), now, result);
            if (current == null) {
                row.setUserType("sys_user");
                row.setStatus(SystemConstants.NORMAL);
                row.setPassword(encodedPassword);
                inserts.add(row);
                result.countAdded();
            } else {
                updates.add(row);
                affected.add(current.getUserId());
                result.countUpdated();
                if (bound) {
                    result.warn(BOUND, r.id(), r.name(), "已绑定同名本地账号");
                }
            }
        }
        for (SysUser user : local.deptBound()) {
            if (!returned.contains(user.getHrmUserId())) {
                result.warn(NOT_RETURNED, user.getHrmUserId(), user.getNickName(), "本次外部来源未返回该人员");
            }
        }
        return new UserSyncPlan(List.copyOf(inserts), List.copyOf(updates), List.copyOf(disables), Set.copyOf(affected), result);
    }

    /**
     * 登录名比较键；数据库按不区分大小写比较 user_name，这里保持一致。
     *
     * @param userName 登录名
     * @return 小写比较键，null 原样返回
     */
    public static String nameKey(String userName) {
        return userName == null ? null : userName.toLowerCase(Locale.ROOT);
    }

    /** 超级管理员和超出数据权限的账号不参与同步。 */
    private boolean allowed(SysUser current, OrgEmployee r, UserSyncLocalState local, UserSyncResultVo result) {
        if (current.isSuperAdmin()) {
            result.skip(r.id(), r.name(), "超级管理员账号不参与同步");
            return false;
        }
        if (!local.scopedUserIds().contains(current.getUserId())) {
            result.skip(r.id(), r.name(), "该账号超出当前数据权限");
            return false;
        }
        return true;
    }

    private void fill(SysUser row, OrgEmployee r, Long deptId, LocalDateTime now, UserSyncResultVo result) {
        row.setDeptId(deptId);
        row.setUserName(r.loginId());
        row.setNickName(r.name());
        row.setGender(r.gender());
        String email = r.email() == null ? "" : r.email();
        if (email.length() > 50) {
            result.warn(FIELD_CLEARED, r.id(), r.name(), "邮箱超过 50 位，已置空");
            email = "";
        }
        String mobile = r.mobile() == null ? "" : r.mobile();
        if (!mobile.isEmpty() && !mobile.matches("\\d{11}")) {
            result.warn(FIELD_CLEARED, r.id(), r.name(), "手机号不是 11 位数字，已置空");
            mobile = "";
        }
        row.setEmail(email);
        row.setPhoneNumber(mobile);
        row.setHrmUserId(r.id());
        row.setHrmWorkcode(r.workcode());
        row.setHrmStatus(r.rawStatus());
        row.setHrmDepartmentId(r.departmentId());
        row.setHrmSubcompanyId(r.subcompanyId());
        row.setHrmLastChangeDate(r.lastChangeDate());
        row.setHrmSyncTime(now);
    }
}
