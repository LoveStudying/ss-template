package org.dromara.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.vo.DeptSyncResultVo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.service.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static org.dromara.system.constant.DeptSyncConstants.*;

/** 同步应用编排：来源检查、权限、锁、事务外快照与独立事务写入。 */
@Service
@RequiredArgsConstructor
public class SysDeptSyncServiceImpl implements ISysDeptSyncService {
    private final SysDeptMapper deptMapper;
    private final ISysDeptService deptService;
    private final OrgSyncSourceProvider sourceProvider;
    private final DeptSyncPlanner planner;
    private final DeptSyncWriter writer;
    private final DeptSyncLock locks;

    /**
     * 同步全部后代，发现额外旧分部锁时释放重取并重新拉取，最多重算一次。
     *
     * @param deptId 点击部门本地主键
     * @return 同步结果
     */
    @Override public DeptSyncResultVo syncChildren(Long deptId) {
        OrgSyncSource source=sourceProvider.require();
        StpUtil.checkPermission("system:dept:sync");
        if (deptId==null) { throw new ServiceException("请选择本地部门"); }
        deptService.checkDeptDataScope(deptId);
        SysDept anchor=deptMapper.selectById(deptId);
        if (anchor==null || !isSupportedType(anchor.getDeptType()) || anchor.getBusinessId()==null || anchor.getHrmSubcompanyId()==null) {
            throw new ServiceException("部门未完整配置组织来源绑定");
        }
        String company=SUBCOMPANY.equals(anchor.getDeptType()) ? anchor.getBusinessId() : anchor.getHrmSubcompanyId();
        Set<String> required=new HashSet<>(Set.of(company));
        for (int attempt=0;attempt<2;attempt++) {
            try (var lease=locks.tryAcquire(Set.copyOf(required))) {
                var snapshot=source.listDepartments(company);
                var local=writer.readState(deptId,snapshot);
                var plan=planner.plan(snapshot,local,LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
                if (!required.containsAll(plan.requiredSubcompanyIds())) {
                    if (attempt==1) { throw new ServiceException("组织归属持续变化，请刷新后重试"); }
                    required.addAll(plan.requiredSubcompanyIds()); continue;
                }
                return writer.write(plan);
            }
        }
        throw new ServiceException("同步范围变化，请重试");
    }
}
