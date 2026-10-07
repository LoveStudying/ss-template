export type SocialAuthMode = 'login' | 'binding';

type AuthorizationStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

const authorizationKey = 'ssoAuthorization';

/** 保存本浏览器发起的授权状态与意图，避免仅根据回调时的令牌判断登录或绑定。 */
export function rememberSocialAuthorization(url: string, mode: SocialAuthMode, storage: AuthorizationStorage) {
  const state = new URL(url).searchParams.get('state');
  if (!state) {
    throw new Error('三生 SSO 授权地址缺少 state，请联系管理员');
  }
  storage.setItem(authorizationKey, JSON.stringify({ state, mode }));
}

/** 验证回调属于本浏览器的一次授权，成功读取后立即消费本地记录。 */
export function readSocialCallback(query: Record<string, unknown>, storage: AuthorizationStorage) {
  if (
    query.source !== 'sso' ||
    typeof query.code !== 'string' ||
    !query.code ||
    typeof query.state !== 'string' ||
    !query.state
  ) {
    throw new Error('三生 SSO 回调参数不完整，请重新发起授权');
  }
  let pending: unknown;
  try {
    pending = JSON.parse(storage.getItem(authorizationKey) ?? 'null');
  } catch {
    throw new Error('三生 SSO 授权记录无效，请重新发起授权');
  }
  if (
    !pending ||
    typeof pending !== 'object' ||
    !('state' in pending) ||
    pending.state !== query.state ||
    !('mode' in pending) ||
    (pending.mode !== 'login' && pending.mode !== 'binding')
  ) {
    throw new Error('三生 SSO 授权状态不匹配，请重新发起授权');
  }
  storage.removeItem(authorizationKey);
  return { code: query.code, state: query.state, mode: pending.mode };
}
