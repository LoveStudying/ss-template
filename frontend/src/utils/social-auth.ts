export type SocialAuthMode = 'login' | 'binding';

type AuthorizationStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

const authorizationKey = 'ssoAuthorization';

/** 仅允许站内业务页面回跳，避免外部地址和登录入口造成重定向循环。 */
export function getSafeLoginRedirect(value: unknown): string {
  if (typeof value !== 'string' || !value) return '/';
  try {
    // 历史登录跳转会额外编码一次；已是路径的值保留查询参数中的编码。
    const path = value.startsWith('/') ? value : decodeURIComponent(value);
    if (
      !path.startsWith('/') ||
      path.startsWith('//') ||
      path.includes('\\') ||
      [...path].some(character => character <= ' ')
    )
      return '/';
    const url = new URL(path, 'https://local.invalid');
    const pathname = decodeURIComponent(url.pathname);
    if (
      pathname.startsWith('//') ||
      pathname.includes('\\') ||
      [...pathname].some(character => character <= ' ') ||
      /^\/(login|social-callback)\/?$/i.test(pathname)
    ) {
      return '/';
    }
    return url.pathname + url.search + url.hash;
  } catch {
    return '/';
  }
}

/** 保存本浏览器发起的授权状态与意图，避免仅根据回调时的令牌判断登录或绑定。 */
export function rememberSocialAuthorization(
  url: string,
  mode: SocialAuthMode,
  storage: AuthorizationStorage,
  redirect?: string
) {
  const state = new URL(url).searchParams.get('state');
  if (!state) {
    throw new Error('三生 SSO 授权地址缺少 state，请联系管理员');
  }
  storage.setItem(authorizationKey, JSON.stringify({ state, mode, redirect: getSafeLoginRedirect(redirect) }));
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
  return {
    code: query.code,
    state: query.state,
    mode: pending.mode,
    redirect: getSafeLoginRedirect('redirect' in pending ? pending.redirect : undefined)
  };
}
