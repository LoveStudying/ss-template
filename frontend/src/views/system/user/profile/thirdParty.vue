<template>
  <div class="profile-auth">
    <el-table :data="auths" border class="data-table profile-auth-table">
      <el-table-column label="序号" width="50" type="index" />
      <el-table-column label="绑定账号平台" width="140" align="center" prop="source" show-overflow-tooltip>
        <template #default="scope">
          {{ scope.row.source === 'sso' ? '三生 SSO' : scope.row.source }}
        </template>
      </el-table-column>
      <el-table-column label="头像" width="120" align="center" prop="avatar">
        <template #default="scope">
          <img :src="scope.row.avatar" style="width: 45px; height: 45px" />
        </template>
      </el-table-column>
      <el-table-column label="第三方账号" width="180" align="center" prop="userName" :show-overflow-tooltip="true" />
      <el-table-column label="绑定时间" width="180" align="center" prop="createTime" />
      <el-table-column label="操作" width="80" align="center" class-name="small-padding fixed-width">
        <template #default="scope">
          <el-tooltip content="解绑" placement="top">
            <el-button link type="primary" icon="CircleClose" @click="unlockAuth(scope.row)"></el-button>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <div class="provider-section">
      <div class="provider-heading">
        <h4 class="provider-desc">可绑定的第三方应用</h4>
        <p>先绑定三生账号，之后即可在登录页使用三生 SSO 登录。</p>
      </div>
      <div class="user-bind">
        <el-button class="third-app" :loading="bindingLoading" @click="authUrl">
          <span class="app-name">绑定三生 SSO</span>
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { authUnlock, authRouterUrl } from '@/api/system/social/auth';
import modal from '@/plugins/modal';
import tab from '@/plugins/tab';
import { propTypes } from '@/utils/propTypes';
import { isHandledRequestError } from '@/utils/request';
import { rememberSocialAuthorization } from '@/utils/social-auth';

const props = defineProps({
  auths: propTypes.any.isRequired
});
const auths = computed(() => props.auths);
const bindingLoading = ref(false);

const unlockAuth = (row: any) => {
  ElMessageBox.confirm('您确定要解除"' + row.source + '"的账号绑定吗？')
    .then(() => {
      return authUnlock(row.id);
    })
    .then((res: any) => {
      if (res.code === 200) {
        modal.msgSuccess('解绑成功');
        tab.refreshPage();
      } else {
        modal.msgError(res.msg);
      }
    })
    .catch(() => {});
};

const authUrl = async () => {
  if (bindingLoading.value) return;
  bindingLoading.value = true;
  try {
    const res = await authRouterUrl('sso', 'binding');
    if (res.code !== 200 || !res.data) {
      throw new Error(res.msg || '无法获取三生 SSO 授权地址');
    }
    rememberSocialAuthorization(res.data, 'binding', sessionStorage);
    window.location.assign(res.data);
  } catch (error) {
    if (!isHandledRequestError(error)) {
      modal.msgError(error instanceof Error ? error.message : '三生 SSO 授权失败');
    }
  } finally {
    bindingLoading.value = false;
  }
};
</script>

<style lang="scss" scoped>
.profile-auth {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.profile-auth-table {
  width: 100%;
}

.provider-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.provider-heading p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
}

.user-bind .third-app {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 84px;
  padding: 12px 10px;
  border: 1px solid var(--app-surface-border);
  border-radius: 12px;
  background: var(--app-elevated-soft-bg);
  transition:
    transform 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.user-bind {
  display: grid;
  grid-template-columns: minmax(160px, 200px);
  gap: 10px;
  justify-content: start;
}

.third-app:hover {
  transform: translateY(-1px);
  border-color: rgba(53, 109, 255, 0.3);
  box-shadow: var(--app-shadow-sm);
}

.provider-desc {
  margin: 0;
  font-size: 16px;
}

td > img {
  height: 20px;
  width: 20px;
  display: inline-block;
  border-radius: 50%;
  margin-right: 5px;
}

.app-name {
  font-weight: 600;
  font-size: 13px;
  line-height: 1.2;
  text-align: center;
  word-break: break-word;
}

html.dark {
  .user-bind .third-app {
    background: rgba(15, 23, 42, 0.64);
  }
}
</style>
