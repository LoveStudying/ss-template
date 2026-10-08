import { getLoginConfig } from '@/api/login';
import { authRouterUrl } from '@/api/system/social/auth';
import { HttpStatus } from '@/enums/RespEnum';
import { rememberSocialAuthorization } from './social-auth';

/** 显式本地入口无需读取默认模式，保证管理员可使用本地账号登录。 */
export async function resolveLoginMode(local: unknown) {
  if (local === 'true') return 'system';
  const res = await getLoginConfig();
  if (res.code !== HttpStatus.SUCCESS || (res.data?.mode !== 'system' && res.data?.mode !== 'sso')) {
    throw new Error(res.msg || '登录模式配置无效，请联系管理员');
  }
  return res.data.mode;
}

/** 保存本次授权及站内回跳路径后，在当前标签页进入 SSO。 */
export async function startSsoLogin(redirect: string, isCurrent: () => boolean = () => true) {
  const res = await authRouterUrl('sso');
  if (!isCurrent()) return;
  if (res.code !== HttpStatus.SUCCESS || !res.data) {
    throw new Error(res.msg || '无法获取三生 SSO 授权地址');
  }
  rememberSocialAuthorization(res.data, 'login', sessionStorage, redirect);
  window.location.assign(res.data);
}
