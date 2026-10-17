package org.dromara.system.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.service.OrgSyncSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 提供当前启用的组织信息来源；来源为 none 时不存在实现。
 */
@Component
public class OrgSyncSourceProvider {

    private final ObjectProvider<OrgSyncSource> sources;

    /**
     * 创建提供者。
     *
     * @param sources 来源实现，可能不存在
     */
    public OrgSyncSourceProvider(ObjectProvider<OrgSyncSource> sources) {
        this.sources = sources;
    }

    /**
     * 返回当前来源。
     *
     * @return 当前来源
     * @throws ServiceException 未启用任何来源
     */
    public OrgSyncSource require() {
        return find().orElseThrow(() -> new ServiceException("未启用组织信息来源，无法同步"));
    }

    /**
     * 返回当前来源或 empty，供部门表单选填校验使用。
     *
     * @return 当前来源
     */
    public Optional<OrgSyncSource> find() {
        return Optional.ofNullable(sources.getIfAvailable());
    }
}
