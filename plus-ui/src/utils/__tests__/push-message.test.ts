import { expect, test } from 'vitest';
import { parsePushMessage, resolveNoticeGroup, resolveNoticeTitle, shouldAppendNotice } from '../push-message';

test('系统消息和通知公告保留分类、内容与消息盒子过滤', () => {
  const system = parsePushMessage('系统提示');
  expect(system.message).toBe('系统提示');
  expect(resolveNoticeGroup(system)).toBe('system');
  expect(resolveNoticeTitle(system)).toBe('系统消息');
  expect(shouldAppendNotice(system)).toBe(true);

  const notice = parsePushMessage(
    JSON.stringify({ type: 'notice', source: 'notice', messageId: 1, message: '公告', path: '/system/notice' })
  );
  expect(notice.messageId).toBe(1);
  expect(notice.path).toBe('/system/notice');
  expect(resolveNoticeGroup(notice)).toBe('notice');
  expect(resolveNoticeTitle(notice)).toBe('通知公告消息');
  expect(shouldAppendNotice(notice)).toBe(true);
  expect(resolveNoticeGroup({ type: 'message', source: 'notice' })).toBe('notice');
  expect(shouldAppendNotice({ type: 'custom', source: 'client' })).toBe(false);
});
