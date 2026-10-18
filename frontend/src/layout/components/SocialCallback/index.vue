<template>
  <div v-loading="loading" class="social-callback">
    <el-result v-if="errorMessage" icon="error" title="三生 SSO 授权失败" :sub-title="errorMessage">
      <template #extra>
        <el-button type="primary" @click="returnToApp">返回系统</el-button>
        <el-button @click="returnToLocalLogin">使用本地账号登录</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import type { LoginData } from '@/api/types';
import { login, callback } from '@/api/login';
import { setToken, getToken } from '@/utils/auth';
import { readSocialCallback } from '@/utils/social-auth';

const route = useRoute();
const loading = ref(true);
const errorMessage = ref('');
let returnPath = '/login';
let loginRedirect = '/';

const returnToApp = () => {
  window.location.replace(import.meta.env.VITE_APP_CONTEXT_PATH.replace(/\/$/, '') + returnPath);
};

const returnToLocalLogin = () => {
  returnPath = '/login?local=true&redirect=' + encodeURIComponent(loginRedirect);
  returnToApp();
};

const init = async () => {
  try {
    const authorization = readSocialCallback(route.query, sessionStorage);
    loginRedirect = authorization.redirect;
    // 清理地址栏中的一次性授权码；实际调用使用已校验的回调快照。
    window.history.replaceState(null, '', window.location.pathname);
    const data: LoginData = {
      socialCode: authorization.code,
      socialState: authorization.state,
      source: 'sso',
      clientId: import.meta.env.VITE_APP_CLIENT_ID,
      grantType: 'social'
    };
    if (authorization.mode === 'binding') {
      if (!getToken()) {
        throw new Error('本地登录已失效，请重新登录后绑定三生账号');
      }
      returnPath = '/user/profile?tab=thirdParty';
      const res = await callback(data);
      if (res.code !== 200) {
        throw new Error(res.msg || '三生账号绑定失败');
      }
      ElMessage.success('三生账号绑定成功');
    } else {
      returnPath = '/login?redirect=' + encodeURIComponent(loginRedirect);
      const res = await login(data);
      if (res.code !== 200 || !res.data?.access_token) {
        throw new Error(res.msg || '三生 SSO 登录失败');
      }
      setToken(res.data.access_token);
      returnPath = loginRedirect;
    }
    returnToApp();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '三生 SSO 授权失败，请重新尝试';
  } finally {
    loading.value = false;
  }
};

onMounted(init);
</script>
