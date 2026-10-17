package org.dromara.system.sync;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.service.OrgSyncSource;
import org.dromara.system.service.impl.OrgSyncSourceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 验证来源提供者在有无来源实现时的行为。
 */
@DisplayName("组织信息来源提供者测试")
class OrgSyncSourceProviderTest {

    @Test
    void noSourceFailsRequireAndFindIsEmpty() {
        var provider = new OrgSyncSourceProvider(new DefaultListableBeanFactory().getBeanProvider(OrgSyncSource.class));
        assertThatThrownBy(provider::require).isInstanceOf(ServiceException.class)
            .hasMessage("未启用组织信息来源，无法同步");
        assertThat(provider.find()).isEmpty();
    }

    @Test
    void returnsRegisteredSource() {
        OrgSyncSource source = mock(OrgSyncSource.class);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("source", source);
        var provider = new OrgSyncSourceProvider(factory.getBeanProvider(OrgSyncSource.class));
        assertThat(provider.require()).isSameAs(source);
        assertThat(provider.find()).containsSame(source);
    }
}
