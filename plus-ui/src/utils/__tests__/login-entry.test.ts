import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { getLoginConfig } from '../../api/login';
import { authRouterUrl } from '../../api/system/social/auth';
import { resolveLoginMode, startSsoLogin } from '../login-entry';
import { readSocialCallback } from '../social-auth';

vi.mock('../../api/login', () => ({ getLoginConfig: vi.fn() }));
vi.mock('../../api/system/social/auth', () => ({ authRouterUrl: vi.fn() }));

const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => values.set(key, value),
  removeItem: (key: string) => values.delete(key)
};
const assign = vi.fn();

afterEach(() => vi.unstubAllGlobals());

beforeEach(() => {
  vi.resetAllMocks();
  values.clear();
  vi.stubGlobal('sessionStorage', storage);
  vi.stubGlobal('window', { location: { assign } });
});

test('普通入口遵循后端配置，本地入口不依赖配置接口', async () => {
  for (const mode of ['system', 'sso'] as const) {
    vi.mocked(getLoginConfig).mockResolvedValue({ code: 200, data: { mode }, msg: '' });
    expect(await resolveLoginMode(undefined)).toBe(mode);
  }
  vi.mocked(getLoginConfig).mockClear();
  expect(await resolveLoginMode('true')).toBe('system');
  expect(getLoginConfig).not.toHaveBeenCalled();
});

test('配置失败或非法模式明确报错，重复 local 参数不能冒充本地入口', async () => {
  vi.mocked(getLoginConfig).mockRejectedValue(new Error('网络不可用'));
  await expect(resolveLoginMode(undefined)).rejects.toThrow('网络不可用');
  vi.mocked(getLoginConfig).mockResolvedValue({ code: 200, data: { mode: 'sso' }, msg: '' });
  expect(await resolveLoginMode(['true', 'false'])).toBe('sso');
  vi.mocked(getLoginConfig).mockResolvedValue({ code: 200, data: undefined, msg: '' });
  await expect(resolveLoginMode(undefined)).rejects.toThrow('登录模式配置无效');
});

test('SSO 跳转先保存 state 和原访问路径，回调只能消费一次', async () => {
  const url = 'https://dev-login.3sbio.com/user/login?state=browser-state';
  vi.mocked(authRouterUrl).mockResolvedValue({ code: 200, data: url, msg: '' });
  await startSsoLogin('/system/user?name=%E5%BC%A0#list');
  expect(assign).toHaveBeenCalledWith(url);
  const query = { source: 'sso', code: 'code', state: 'browser-state' };
  expect(readSocialCallback(query, storage).redirect).toBe('/system/user?name=%E5%BC%A0#list');
  expect(() => readSocialCallback(query, storage)).toThrow();
});

test('授权失败或缺少 state 时不跳转浏览器', async () => {
  vi.mocked(authRouterUrl).mockRejectedValue(new Error('SSO 配置未完成'));
  await expect(startSsoLogin('/system/user')).rejects.toThrow('SSO 配置未完成');
  expect(assign).not.toHaveBeenCalled();
  vi.mocked(authRouterUrl).mockResolvedValue({ code: 200, data: 'https://sso.example/login', msg: '' });
  await expect(startSsoLogin('/')).rejects.toThrow('缺少 state');
  expect(assign).not.toHaveBeenCalled();
});

test('切换入口或离开页面后，迟到的授权响应不保存记录也不跳转', async () => {
  const authorization = Promise.withResolvers<Awaited<ReturnType<typeof authRouterUrl>>>();
  vi.mocked(authRouterUrl).mockReturnValue(authorization.promise);
  let active = true;
  const pending = startSsoLogin('/system/user', () => active);
  active = false;
  authorization.resolve({ code: 200, data: 'https://dev-login.3sbio.com/user/login?state=old-state' });
  await pending;
  expect(assign).not.toHaveBeenCalled();
  expect(values.size).toBe(0);
});
