package org.dromara.common.social;

import cn.hutool.extra.spring.SpringUtil;
import io.netty.buffer.ByteBuf;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.social.utils.AuthRedisStateCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.handler.State;
import org.redisson.codec.TypedJsonJackson3Codec;
import org.springframework.context.support.StaticApplicationContext;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("绑定授权状态的用户归属及一次性消费测试")
class AuthBindingStateTest {

    @Test
    @DisplayName("绑定状态只允许发起用户消费一次")
    void shouldConsumeBindingOnlyForInitiatingUser() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> bucket = mock(RBucket.class);
        Map<String, ByteBuf> values = new HashMap<>();
        // 与 RedisConfig 相同的 Object 编码及默认类型策略，保留真实数字类型转换。
        JsonMapper mapper = JsonMapper.builder()
            .activateDefaultTyping(BasicPolymorphicTypeValidator.builder()
                .allowIfSubType((ctxt, clazz) -> true).build(), DefaultTyping.NON_FINAL)
            .build();
        TypedJsonJackson3Codec codec = new TypedJsonJackson3Codec(Object.class, mapper);
        AtomicReference<String> key = new AtomicReference<>();
        when(client.<Object>getBucket(anyString())).thenAnswer(invocation -> {
            key.set(invocation.getArgument(0));
            return bucket;
        });
        when(bucket.getAndDelete()).thenAnswer(invocation -> {
            ByteBuf encoded = values.remove(key.get());
            if (encoded == null) {
                return null;
            }
            try {
                return codec.getValueDecoder().decode(encoded, new State());
            } finally {
                encoded.release();
            }
        });
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("redissonClient", client);
        context.refresh();
        new SpringUtil().setApplicationContext(context);
        try (var redis = mockStatic(RedisUtils.class)) {
            redis.when(RedisUtils::getClient).thenReturn(client);
            redis.when(() -> RedisUtils.setCacheObject(anyString(), any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    values.put(invocation.getArgument(0), codec.getValueEncoder().encode(invocation.getArgument(1)));
                    return null;
                });
            AuthRedisStateCache cache = new AuthRedisStateCache();

            cache.cacheBindingUser("state-one", 1L);
            assertTrue(cache.consumeBindingUser("state-one", 1L));
            assertFalse(cache.consumeBindingUser("state-one", 1L));
            cache.cacheBindingUser("state-two", 1L);
            assertFalse(cache.consumeBindingUser("state-two", 2L));
            assertFalse(cache.consumeBindingUser("state-two", 1L));
            assertFalse(cache.consumeBindingUser("expired-state", 1L));
        } finally {
            values.values().forEach(ByteBuf::release);
            context.close();
        }
    }
}
