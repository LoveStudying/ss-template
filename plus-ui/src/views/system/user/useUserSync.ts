import { ref } from 'vue';
import type { UserSyncResultVO } from '@/api/system/user/types';
import type { AxiosPromise } from '@/utils/api-types';

interface Dependencies {
  sync: (deptId: string | number) => AxiosPromise<UserSyncResultVO>;
  confirm: (deptName: string) => Promise<unknown>;
  reload: () => Promise<void>;
  notifyResult: (result: UserSyncResultVO) => void;
  notifyError: (message: string) => void;
}

const isTimeoutError = (error: unknown): boolean =>
  typeof error === 'object' &&
  error !== null &&
  'code' in error &&
  (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT');

/**
 * 按部门从组织信息来源同步员工：确认、请求、展示结果并刷新列表。
 * 不在前端预检来源、部门类型与绑定，由后端返回原因、请求封装提示。
 */
export function useUserSync(deps: Dependencies) {
  const isSyncing = ref(false);
  const syncDeptUsers = async (deptId?: string | number, deptName?: string): Promise<void> => {
    if (isSyncing.value || deptId === undefined || deptId === null || deptId === '') return;
    isSyncing.value = true;
    try {
      try {
        await deps.confirm(deptName ?? '');
      } catch {
        return;
      }
      let result: UserSyncResultVO;
      try {
        const response = await deps.sync(deptId);
        if (!response.data) {
          deps.notifyError('同步响应缺少结果，请刷新确认服务端状态');
          return;
        }
        result = response.data;
      } catch (error: unknown) {
        // 业务失败由请求封装提示；超时需要单独说明结果未知
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
    }
  };
  return { isSyncing, syncDeptUsers };
}
