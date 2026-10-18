package org.dromara.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.constant.DeptSyncConstants;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.sync.OrgEmployee;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.UserSyncResultVo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.service.ISysConfigService;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.IUserSyncService;
import org.dromara.system.service.OrgSyncSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.dromara.system.constant.UserSyncConstants.INIT_PASSWORD_KEY;
import static org.dromara.system.constant.UserSyncConstants.LOCK_PREFIX;

/**
 * 员工同步编排：来源检查、前置校验、部门锁、事务外拉取、计算与写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSyncServiceImpl implements IUserSyncService {
    private final SysDeptMapper deptMapper;
    private final ISysDeptService deptService;
    private final ISysConfigService configService;
    private final OrgSyncSourceProvider sourceProvider;
    private final RedissonClient redissonClient;
    private final UserSyncPlanner planner;
    private final UserSyncWriter writer;

    /**
     * 按部门绑定的外部部门 ID 拉取员工并写入本地用户。
     *
     * @param deptId 本地普通部门 ID
     * @return 同步统计与提示
     */
    @Override
    public UserSyncResultVo syncDepartment(Long deptId) {
        OrgSyncSource source = sourceProvider.require();
        if (deptId == null) {
            throw new ServiceException("请选择部门");
        }
        SysDept dept = deptMapper.selectById(deptId);
        if (dept == null) {
            throw new ServiceException("部门不存在");
        }
        deptService.checkDeptDataScope(deptId);
        if (!DeptSyncConstants.DEPARTMENT.equals(dept.getDeptType())) {
            throw new ServiceException("请选择普通部门进行员工同步");
        }
        if (StringUtils.isBlank(dept.getBusinessId())) {
            throw new ServiceException("该部门未绑定外部部门 ID，无法同步员工");
        }
        String initPassword = configService.selectConfigByKey(INIT_PASSWORD_KEY);
        if (StringUtils.isBlank(initPassword)) {
            throw new ServiceException("未配置用户初始密码参数 " + INIT_PASSWORD_KEY);
        }
        RLock lock = redissonClient.getLock(LOCK_PREFIX + deptId);
        // 不指定 leaseTime，由 watchdog 覆盖网络读取和写库的完整时长
        if (!lock.tryLock()) {
            throw new ServiceException("该部门正在同步，请稍后重试");
        }
        try {
            String sourceDepartmentId = dept.getBusinessId().trim();
            UserSyncResultVo result = new UserSyncResultVo();
            result.setDeptId(deptId);
            result.setSourceDepartmentId(sourceDepartmentId);
            List<OrgEmployee> employees = dedup(source.listEmployees(sourceDepartmentId), result);
            result.setFetched(employees.size());
            var local = writer.readState(deptId, employees);
            var plan = planner.plan(readDepartments(employees), employees, local, BCrypt.hashpw(initPassword),
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")), result, source::isActiveStatus);
            try {
                return writer.write(plan);
            } catch (ServiceException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("员工同步写入失败 deptId={} causeType={}", deptId, e.getClass().getSimpleName());
                throw new ServiceException("员工同步写入失败，请重试");
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 人员 ID 为空或同批重复的记录跳过并写入提示，保留首次出现的记录。 */
    private static List<OrgEmployee> dedup(List<OrgEmployee> employees, UserSyncResultVo result) {
        List<OrgEmployee> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OrgEmployee e : employees) {
            if (e.id() == null) {
                result.skip(null, e.name(), "外部人员 ID 为空");
                continue;
            }
            if (!seen.add(e.id())) {
                result.skip(e.id(), e.name(), "外部来源返回重复人员 ID");
                continue;
            }
            kept.add(e);
        }
        return kept;
    }

    /** 按员工所属外部部门批量匹配未删除且有数据权限的普通部门，停用部门仍可接收员工。 */
    private Map<String, List<Long>> readDepartments(List<OrgEmployee> employees) {
        List<String> ids = employees.stream().map(OrgEmployee::departmentId)
            .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return deptMapper.selectDeptList(Wrappers.<SysDept>lambdaQuery()
                .in(SysDept::getBusinessId, ids)
                .eq(SysDept::getDeptType, DeptSyncConstants.DEPARTMENT)
                .eq(SysDept::getDelFlag, "0"))
            .stream().collect(Collectors.groupingBy(SysDeptVo::getBusinessId,
                Collectors.mapping(SysDeptVo::getDeptId, Collectors.toList())));
    }
}
