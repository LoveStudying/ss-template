import { describe, expect, it, vi } from 'vitest';
import type { DeptSyncResult, DeptVO } from '@/api/system/dept/types';
import { useDeptSync } from '../../views/system/dept/useDeptSync';

const row: DeptVO = {
  id: 100,
  deptId: 100,
  parentId: 0,
  parentName: '',
  deptName: '分部',
  deptCategory: '',
  children: [],
  orderNum: 0,
  leader: '',
  phone: '',
  email: '',
  status: '0',
  delFlag: '0',
  ancestors: '0',
  menuId: '',
  deptType: 'subcompany',
  businessId: '5',
  hrmSubcompanyId: '5'
};
const result: DeptSyncResult = {
  deptId: 100,
  subcompanyId: '5',
  syncTime: '2026-10-13 12:00:00',
  fetchedCount: 983,
  selectedCount: 983,
  createdCount: 980,
  updatedCount: 2,
  unchangedCount: 1,
  statusSkippedCount: 25,
  warningCount: 25,
  warningsTruncated: true,
  warnings: [{ businessId: '2977', reason: 'HAS_USERS', localStatus: '0', hrmCanceled: '1' }]
};
const dependencies = () => ({
  sync: vi.fn().mockResolvedValue({ code: 200, data: result }),
  confirm: vi.fn().mockResolvedValue(undefined),
  reload: vi.fn().mockResolvedValue(undefined),
  notifyResult: vi.fn(),
  notifyError: vi.fn()
});

describe('部门组织来源同步异步流程', () => {
  it('取消确认时不发请求并恢复入口', async () => {
    const deps = dependencies();
    deps.confirm.mockRejectedValue('cancel');
    const flow = useDeptSync(deps);
    await flow.syncChildren(row);
    expect(deps.sync).not.toHaveBeenCalled();
    expect(deps.notifyError).not.toHaveBeenCalled();
    expect(flow.isSyncing.value).toBe(false);
  });

  it('连续点击仅发一个请求且所有入口共享等待状态', async () => {
    const deps = dependencies();
    let release: (() => void) | undefined;
    deps.sync.mockImplementation(
      () =>
        new Promise(resolve => {
          release = () => resolve({ data: result });
        })
    );
    const flow = useDeptSync(deps);
    const first = flow.syncChildren(row);
    await Promise.resolve();
    expect(flow.isSyncing.value).toBe(true);
    expect(flow.syncingDeptId.value).toBe(100);
    await flow.syncChildren({ ...row, deptId: 101 });
    expect(deps.sync).toHaveBeenCalledTimes(1);
    release?.();
    await first;
    expect(flow.isSyncing.value).toBe(false);
  });

  it('成功传递完整警告统计并刷新', async () => {
    const deps = dependencies();
    await useDeptSync(deps).syncChildren(row);
    expect(deps.notifyResult).toHaveBeenCalledWith(result);
    expect(deps.reload).toHaveBeenCalledTimes(1);
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('sendsRequestEvenWithoutBinding：未绑定部门确认后仍调用接口，由后端返回原因', async () => {
    const deps = dependencies();
    const unbound: DeptVO = { ...row, deptType: undefined, businessId: undefined, hrmSubcompanyId: undefined };
    await useDeptSync(deps).syncChildren(unbound);
    expect(deps.confirm).toHaveBeenCalledTimes(1);
    expect(deps.sync).toHaveBeenCalledWith(100);
  });

  it('businessErrorIsNotRetoldByComposable：业务失败由请求封装提示，组合式函数不重复提示', async () => {
    const deps = dependencies();
    deps.sync.mockRejectedValue(new Error('未启用组织信息来源，无法同步'));
    const flow = useDeptSync(deps);
    await flow.syncChildren(row);
    expect(flow.isSyncing.value).toBe(false);
    expect(flow.syncingDeptId.value).toBeUndefined();
    expect(deps.reload).not.toHaveBeenCalled();
    expect(deps.notifyResult).not.toHaveBeenCalled();
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('超时明确提示结果待刷新确认', async () => {
    const deps = dependencies();
    deps.sync.mockRejectedValue({ code: 'ECONNABORTED' });
    const flow = useDeptSync(deps);
    await flow.syncChildren(row);
    expect(deps.notifyError).toHaveBeenCalledWith(expect.stringContaining('刷新确认'));
    expect(flow.isSyncing.value).toBe(false);
  });

  it('服务端成功后列表刷新失败仍提示同步已完成', async () => {
    const deps = dependencies();
    deps.reload.mockRejectedValue(new Error('刷新失败'));
    await useDeptSync(deps).syncChildren(row);
    expect(deps.notifyResult).toHaveBeenCalledWith(result);
    expect(deps.notifyError).toHaveBeenCalledWith(expect.stringContaining('同步已完成'));
  });
});
