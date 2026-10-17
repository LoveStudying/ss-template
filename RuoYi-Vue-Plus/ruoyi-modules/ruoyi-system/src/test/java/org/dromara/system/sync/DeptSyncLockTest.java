package org.dromara.system.sync;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.service.impl.DeptSyncLock;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证分部同步锁的取锁顺序、逆序释放与中断处理。 */
class DeptSyncLockTest {
    @Test void locksInStableOrderAndReleasesInReverseOrder() throws Exception {
        RedissonClient redis=mock(RedissonClient.class); RLock a=mock(RLock.class); RLock b=mock(RLock.class);
        when(redis.getLock("system:dept:sync:10")).thenReturn(a); when(redis.getLock("system:dept:sync:5")).thenReturn(b);
        when(a.tryLock(0,TimeUnit.MILLISECONDS)).thenReturn(true); when(b.tryLock(0,TimeUnit.MILLISECONDS)).thenReturn(true);
        when(a.isHeldByCurrentThread()).thenReturn(true); when(b.isHeldByCurrentThread()).thenReturn(true);
        try (var lease=new DeptSyncLock(redis).tryAcquire(Set.of("5","10"))) { assertThat(lease).isNotNull(); }
        var order=inOrder(a,b); order.verify(a).tryLock(0,TimeUnit.MILLISECONDS); order.verify(b).tryLock(0,TimeUnit.MILLISECONDS);
        order.verify(b).isHeldByCurrentThread(); order.verify(b).unlock(); order.verify(a).isHeldByCurrentThread(); order.verify(a).unlock();
    }

    @Test void partialFailureReleasesAcquiredLockAndInterruptionIsPreserved() throws Exception {
        RedissonClient redis=mock(RedissonClient.class); RLock a=mock(RLock.class); RLock b=mock(RLock.class);
        when(redis.getLock("system:dept:sync:1")).thenReturn(a); when(redis.getLock("system:dept:sync:2")).thenReturn(b);
        when(a.tryLock(0,TimeUnit.MILLISECONDS)).thenReturn(true); when(a.isHeldByCurrentThread()).thenReturn(true);
        assertThatThrownBy(() -> new DeptSyncLock(redis).tryAcquire(Set.of("1","2"))).isInstanceOf(ServiceException.class);
        verify(a).unlock(); when(b.tryLock(0,TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> new DeptSyncLock(redis).tryAcquire(Set.of("1","2"))).isInstanceOf(ServiceException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
