import { beforeEach, expect, test } from 'vitest';
import { rememberSocialAuthorization, readSocialCallback } from '../social-auth';

const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => values.set(key, value),
  removeItem: (key: string) => values.delete(key)
};
const callback = { source: 'sso', code: 'authorization-code', state: 'browser-state' };

beforeEach(() => values.clear());

test('回调沿用发起授权时的登录或绑定意图，且只能处理一次', () => {
  for (const mode of ['login', 'binding'] as const) {
    rememberSocialAuthorization('https://dev-login.3sbio.com/user/login?state=browser-state', mode, storage);
    expect(readSocialCallback(callback, storage)).toEqual({ code: 'authorization-code', state: 'browser-state', mode });
    expect(() => readSocialCallback(callback, storage)).toThrow();
  }
});

test('缺少或伪造 state、重复参数和其他平台回调均拒绝处理', () => {
  rememberSocialAuthorization('https://dev-login.3sbio.com/user/login?state=browser-state', 'binding', storage);
  for (const query of [
    { ...callback, state: undefined },
    { ...callback, state: 'forged-state' },
    { ...callback, state: ['browser-state', 'forged-state'] },
    { ...callback, code: '' },
    { ...callback, source: 'github' }
  ]) {
    expect(() => readSocialCallback(query, storage)).toThrow();
  }
  expect(readSocialCallback(callback, storage).mode).toBe('binding');
});

test('授权地址缺少 state 或本地记录损坏时拒绝处理', () => {
  expect(() => rememberSocialAuthorization('https://dev-login.3sbio.com/user/login', 'login', storage)).toThrow();
  rememberSocialAuthorization('https://dev-login.3sbio.com/user/login?state=browser-state', 'login', storage);
  const key = [...values.keys()][0];
  storage.setItem(key, 'not-json');
  expect(() => readSocialCallback(callback, storage)).toThrow();
  storage.setItem(key, JSON.stringify({ state: 'browser-state', mode: 'unknown' }));
  expect(() => readSocialCallback(callback, storage)).toThrow();
});
