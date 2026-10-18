import { ref } from 'vue';
import type { DeptSyncResult, DeptVO } from '@/api/system/dept/types';
import type { AxiosPromise } from '@/utils/api-types';

interface Dependencies {
  sync: (deptId: string | number) => AxiosPromise<DeptSyncResult>;
  confirm: (row: DeptVO) => Promise<unknown>;
  reload: () => Promise<void>;
  notifyResult: (result: DeptSyncResult) => void;
  notifyError: (message: string) => void;
}

const isTimeoutError = (error: unknown): boolean =>
  typeof error === 'object' &&
  error !== null &&
  'code' in error &&
  (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT');

/**
 * 部门子树同步流程：一页共享互斥状态，区分请求结果未知与提交后列表刷新失败。
 * 不在前端预检绑定，未绑定或未启用来源时由后端返回原因、请求封装提示。
 */
export function useDeptSync(deps: Dependencies) {
  const isSyncing = ref(false);
  const syncingDeptId = ref<string | number>();
  const syncChildren = async (row: DeptVO): Promise<void> => {
    if (isSyncing.value) return;
    isSyncing.value = true;
    syncingDeptId.value = row.deptId;
    try {
      try {
        await deps.confirm(row);
      } catch {
        return;
      }
      let result: DeptSyncResult;
      try {
        const response = await deps.sync(row.deptId);
        if (!response.data) {
          deps.notifyError('同步响应缺少结果，请刷新确认服务端状态');
          return;
        }
        result = response.data;
      } catch (error: unknown) {
        if (isTimeoutError(error)) {
          deps.notifyError('请求超时，服务端结果尚未确定，请刷新确认后再重试');
        }
        return;
      }
      deps.notifyResult(result);
      try {
        await deps.reload();
      } catch {
        deps.notifyError('同步已完成，列表刷新失败，请手动刷新');
      }
    } finally {
      isSyncing.value = false;
      syncingDeptId.value = undefined;
    }
  };
  return { isSyncing, syncingDeptId, syncChildren };
}
