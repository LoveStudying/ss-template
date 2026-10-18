package org.dromara.system.service.impl;

import org.dromara.common.core.enums.PushSourceEnum;
import org.dromara.common.core.enums.PushTypeEnum;
import cn.hutool.extra.spring.SpringUtil;
import org.dromara.system.api.domain.PushPayloadDTO;
import org.dromara.system.domain.SysMessage;
import org.dromara.system.mapper.SysMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SysMessageServiceImplTest {

    /**
     * 验证保留的系统消息和通知公告仍按分类和接收人存储。
     */
    @Test
    void shouldStoreSystemAndNoticeMessages() {
        var mapper = mock(SysMessageMapper.class);
        var service = new SysMessageServiceImpl(mapper);
        try (var spring = mockStatic(SpringUtil.class)) {
            spring.when(() -> SpringUtil.getBean(JsonMapper.class)).thenReturn(JsonMapper.builder().build());
            var system = PushPayloadDTO.of(PushTypeEnum.MESSAGE, PushSourceEnum.BACKEND, "系统消息", null);
            system.setMessageId(100L);
            assertSame(system, service.storeUsers(List.of(10L, 20L), system));

            var notice = PushPayloadDTO.of(PushTypeEnum.NOTICE, PushSourceEnum.NOTICE, "公告", null);
            notice.setMessageId(101L);
            assertSame(notice, service.storeAll(notice));

            var messages = ArgumentCaptor.forClass(SysMessage.class);
            verify(mapper, org.mockito.Mockito.times(2)).insert(messages.capture());
            assertEquals("system", messages.getAllValues().get(0).getCategory());
            assertEquals("10,20", messages.getAllValues().get(0).getSendUserIds());
            assertEquals("notice", messages.getAllValues().get(1).getCategory());
            assertEquals("0", messages.getAllValues().get(1).getSendUserIds());
        }
    }

    /**
     * 验证空消息和自定义推送不会被错误存入消息盒子。
     */
    @Test
    void shouldSkipNullAndCustomMessages() {
        var mapper = mock(SysMessageMapper.class);
        var service = new SysMessageServiceImpl(mapper);
        assertNull(service.storeAll(null));
        var custom = PushPayloadDTO.of(PushTypeEnum.CUSTOM, PushSourceEnum.CLIENT, "自定义消息", null);
        assertSame(custom, service.storeAll(custom));
        verifyNoInteractions(mapper);
    }
}
