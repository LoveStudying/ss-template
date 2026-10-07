package org.dromara.common.social.utils;

import lombok.AllArgsConstructor;
import me.zhyd.oauth.cache.AuthStateCache;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.redis.utils.RedisUtils;

import java.time.Duration;

/**
 * 授权状态缓存
 */
@AllArgsConstructor
public class AuthRedisStateCache implements AuthStateCache {

    /**
     * 保存本次绑定授权的发起用户，使用独立键避免影响 JustAuth 的 state 校验。
     *
     * @param state 授权随机状态
     * @param userId 已登录的本地用户 ID
     */
    public void cacheBindingUser(String state, Long userId) {
        // 默认 Redis JSON 编码按 Object 读取，小编号数字可能被还原成 Integer。
        RedisUtils.setCacheObject(GlobalConstants.SOCIAL_AUTH_CODE_KEY + "binding:" + state, userId.toString(), Duration.ofMinutes(3));
    }

    /**
     * 原子消费绑定状态并检查用户归属，防止跨账号绑定及重复回调。
     *
     * @param state 回调授权状态
     * @param userId 当前本地用户 ID
     * @return 当前用户为发起用户且状态尚未过期或消费时返回 true
     */
    public boolean consumeBindingUser(String state, Long userId) {
        String initiatingUserId = RedisUtils.getClient().<String>getBucket(GlobalConstants.SOCIAL_AUTH_CODE_KEY + "binding:" + state).getAndDelete();
        return userId != null && userId.toString().equals(initiatingUserId);
    }

    /**
     * 存入缓存
     *
     * @param key   缓存key
     * @param value 缓存内容
     */
    @Override
    public void cache(String key, String value) {
        // 授权超时时间 默认三分钟
        RedisUtils.setCacheObject(GlobalConstants.SOCIAL_AUTH_CODE_KEY + key, value, Duration.ofMinutes(3));
    }

    /**
     * 存入缓存
     *
     * @param key     缓存key
     * @param value   缓存内容
     * @param timeout 指定缓存过期时间(毫秒)
     */
    @Override
    public void cache(String key, String value, long timeout) {
        RedisUtils.setCacheObject(GlobalConstants.SOCIAL_AUTH_CODE_KEY + key, value, Duration.ofMillis(timeout));
    }

    /**
     * 获取缓存内容
     *
     * @param key 缓存key
     * @return 缓存内容
     */
    @Override
    public String get(String key) {
        return RedisUtils.getCacheObject(GlobalConstants.SOCIAL_AUTH_CODE_KEY + key);
    }

    /**
     * 是否存在key，如果对应key的value值已过期，也返回false
     *
     * @param key 缓存key
     * @return true：存在key，并且value没过期；false：key不存在或者已过期
     */
    @Override
    public boolean containsKey(String key) {
        return RedisUtils.hasKey(GlobalConstants.SOCIAL_AUTH_CODE_KEY + key);
    }
}
