import { describe, expect, it, vi } from 'vitest';
import type { UserSyncResultVO } from '@/api/system/user/types';
import { useUserSync } from '../../views/system/user/useUserSync';

const result: UserSyncResultVO = {
  deptId: 300,
  sourceDepartmentId: '1885',
  fetched: 4,
  added: 1,
  updated: 0,
  disabled: 0,
  skipped: 0,
  warnings: []
};
const dependencies = () => ({
  sync: vi.fn().mockResolvedValue({ code: 200, data: result }),
  confirm: vi.fn().mockResolvedValue(undefined),
  reload: vi.fn().mockResolvedValue(undefined),
  notifyResult: vi.fn(),
  notifyError: vi.fn()
});

describe('用户页按部门同步员工', () => {
  it('ignoresWhenNoDeptSelected：未选部门时不确认、不请求', async () => {
    const deps = dependencies();
    const flow = useUserSync(deps);
    await flow.syncDeptUsers(undefined, undefined);
    expect(deps.confirm).not.toHaveBeenCalled();
    expect(deps.sync).not.toHaveBeenCalled();
    expect(flow.isSyncing.value).toBe(false);
  });

  it('confirmsThenSyncsWithoutPrecheck：直接确认后同步、展示结果并刷新', async () => {
    const deps = dependencies();
    await useUserSync(deps).syncDeptUsers(300, '研发部');
    expect(deps.confirm).toHaveBeenCalledWith('研发部');
    expect(deps.sync).toHaveBeenCalledWith(300);
    expect(deps.notifyResult).toHaveBeenCalledWith(result);
    expect(deps.reload).toHaveBeenCalledTimes(1);
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('取消确认时不发请求并恢复入口', async () => {
    const deps = dependencies();
    deps.confirm.mockRejectedValue('cancel');
    const flow = useUserSync(deps);
    await flow.syncDeptUsers(300, '研发部');
    expect(deps.sync).not.toHaveBeenCalled();
    expect(deps.notifyError).not.toHaveBeenCalled();
    expect(flow.isSyncing.value).toBe(false);
  });

  it('timeoutReportsUnknownResult：请求超时提示结果未知', async () => {
    const deps = dependencies();
    deps.sync.mockRejectedValue({ code: 'ECONNABORTED' });
    const flow = useUserSync(deps);
    await flow.syncDeptUsers(300, '研发部');
    expect(deps.notifyError).toHaveBeenCalledWith('请求超时，服务端结果尚未确定，请刷新确认后再重试');
    expect(deps.notifyResult).not.toHaveBeenCalled();
    expect(flow.isSyncing.value).toBe(false);
  });

  it('业务失败由请求封装提示，组合式函数不重复提示', async () => {
    const deps = dependencies();
    deps.sync.mockRejectedValue(new Error('未启用组织信息来源，无法同步'));
    const flow = useUserSync(deps);
    await flow.syncDeptUsers(300, '研发部');
    expect(deps.notifyError).not.toHaveBeenCalled();
    expect(deps.notifyResult).not.toHaveBeenCalled();
    expect(deps.reload).not.toHaveBeenCalled();
    expect(flow.isSyncing.value).toBe(false);
  });

  it('响应缺少结果时提示刷新确认', async () => {
    const deps = dependencies();
    deps.sync.mockResolvedValue({ code: 200, data: undefined });
    await useUserSync(deps).syncDeptUsers(300, '研发部');
    expect(deps.notifyError).toHaveBeenCalledWith('同步响应缺少结果，请刷新确认服务端状态');
    expect(deps.notifyResult).not.toHaveBeenCalled();
  });

  it('刷新失败时提示同步已完成', async () => {
    const deps = dependencies();
    deps.reload.mockRejectedValue(new Error('x'));
    await useUserSync(deps).syncDeptUsers(300, '研发部');
    expect(deps.notifyResult).toHaveBeenCalledWith(result);
    expect(deps.notifyError).toHaveBeenCalledWith('同步已完成，列表刷新失败，请手动刷新');
  });

  it('进行中重复点击被忽略', async () => {
    const deps = dependencies();
    const { syncDeptUsers } = useUserSync(deps);
    await Promise.all([syncDeptUsers(300, '研发部'), syncDeptUsers(300, '研发部')]);
    expect(deps.confirm).toHaveBeenCalledTimes(1);
    expect(deps.sync).toHaveBeenCalledTimes(1);
  });
});
