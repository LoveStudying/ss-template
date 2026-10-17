package org.dromara.system.sync;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.system.controller.system.SysUserController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证员工同步接口的路径、权限、审计与防重注解。 */
class UserSyncControllerTest {
    @Test
    void syncRouteHasPermissionAuditAndRepeatProtection() throws Exception {
        var method = SysUserController.class.getMethod("syncEmployees", Long.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly("/{deptId}/sync");
        assertThat(method.getAnnotation(SaCheckPermission.class).value()).containsExactly("system:user:sync");
        Log log = method.getAnnotation(Log.class);
        assertThat(log.title()).isEqualTo("用户员工同步");
        assertThat(log.businessType()).isEqualTo(BusinessType.UPDATE);
        assertThat(method.getAnnotation(RepeatSubmit.class)).isNotNull();
    }
}
