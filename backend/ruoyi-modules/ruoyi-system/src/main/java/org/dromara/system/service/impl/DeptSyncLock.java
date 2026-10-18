package org.dromara.system.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.constant.DeptSyncConstants;
import org.redisson.api.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 分部级 Redisson watchdog 互斥，覆盖分部和其任意子树同步。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeptSyncLock {
    private final RedissonClient redissonClient;

    /** 非阻塞按字符串稳定顺序取锁；部分失败时释放已持有的全部锁。 */
    public Lease tryAcquire(Set<String> subcompanyIds) {
        Lease lease=new Lease();
        try {
            for (String id : new TreeSet<>(subcompanyIds)) {
                OrgSnapshotValidator.validateId(id,false);
                RLock lock=redissonClient.getLock(DeptSyncConstants.LOCK_PREFIX+id);
                // 不指定 leaseTime，交由 watchdog 覆盖网络读取与数据库提交的完整时长。
                if (!lock.tryLock(0,TimeUnit.MILLISECONDS)) { throw new ServiceException("该分部正在同步，请稍后重试"); }
                lease.held.add(lock);
            }
            return lease;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); lease.close(); throw new ServiceException("同步取锁被中断，请重试");
        } catch (RuntimeException e) { lease.close(); throw e; }
    }

    /** 持有的分部锁，按逆序释放；可安全重复关闭。 */
    public static final class Lease implements AutoCloseable {
        private final List<RLock> held=new ArrayList<>();
        private Lease() { }
        /** 释放全部已获取锁，单个释放失败不会遗漏其余锁。 */
        @Override public void close() {
            for (int i=held.size()-1;i>=0;i--) {
                RLock lock=held.get(i);
                try { if (lock.isHeldByCurrentThread()) { lock.unlock(); } }
                catch (RuntimeException e) { log.error("组织来源同步锁释放失败 causeType={}",e.getClass().getSimpleName()); }
            }
            held.clear();
        }
    }
}
