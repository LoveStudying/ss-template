package org.dromara.system.sync;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import cn.dev33.satoken.stp.*;
import cn.dev33.satoken.strategy.SaStrategy;
import org.dromara.system.controller.system.SysDeptController;
import org.dromara.system.domain.vo.DeptSyncResultVo;
import org.dromara.system.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证部门子树同步接口的权限、审计与防重注解及真实拦截器行为。 */
class DeptSyncControllerTest {
    @Test void syncRouteHasAuditAndRepeatProtection() throws Exception {
        var method=SysDeptController.class.getMethod("syncChildren",Long.class);
        assertThat(method.getAnnotation(org.dromara.common.log.annotation.Log.class)).isNotNull();
        assertThat(method.getAnnotation(org.dromara.common.redis.annotation.RepeatSubmit.class)).isNotNull();
        assertThat(method.getAnnotation(cn.dev33.satoken.annotation.SaCheckPermission.class).value()).containsExactly("system:dept:sync");
    }
    @Configuration
    @EnableWebMvc
    static class WebConfig implements WebMvcConfigurer {
        @Bean ISysDeptSyncService syncService() {
            ISysDeptSyncService service=mock(ISysDeptSyncService.class);
            DeptSyncResultVo result=new DeptSyncResultVo(); result.setDeptId(100L); result.setSubcompanyId("5");
            when(service.syncChildren(100L)).thenReturn(result); return service;
        }
        @Bean SysDeptController controller(ISysDeptSyncService sync) { return new SysDeptController(mock(ISysDeptService.class),mock(ISysPostService.class),sync); }
        @Bean LoginEndpoint loginEndpoint() { return new LoginEndpoint(); }
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new SaInterceptor(h -> StpUtil.checkLogin())).excludePathPatterns("/test/login/*");
        }
    }
    @RestController static class LoginEndpoint {
        @PostMapping("/test/login/{id}") public String login(@PathVariable String id) { StpUtil.login(id); return StpUtil.getTokenValue(); }
    }

    @Test void realInterceptorRejectsAnonymousAndMissingPermissionButAcceptsSyncOnly() throws Exception {
        var oldConfig=SaManager.getConfig(); var oldDao=SaManager.getSaTokenDao();
        var oldContext=SaManager.getSaTokenContext(); var oldPermissions=SaManager.getStpInterface();
        // Sa-Token 1.46 起 Servlet 请求模型策略由 SaTokenContextRegister 构造时注册（原由 Boot 自动配置触发），测试后还原全局策略。
        var strategy=SaStrategy.instance; var oldRequest=strategy.createSaRequest; var oldResponse=strategy.createSaResponse;
        var oldStorage=strategy.createSaStorage; var oldMatcher=strategy.routeMatcher;
        new SaTokenContextRegister();
        SaTokenDaoDefaultImpl dao=new SaTokenDaoDefaultImpl();
        try (var context=new AnnotationConfigWebApplicationContext()) {
            SaManager.setConfig(new SaTokenConfig().setTokenName("dept-test-token").setIsPrint(false));
            SaManager.setSaTokenDao(dao); SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
            SaManager.setStpInterface(new StpInterface() {
                @Override public List<String> getPermissionList(Object id,String type) { return "sync".equals(id) ? List.of("system:dept:sync") : List.of(); }
                @Override public List<String> getRoleList(Object id,String type) { return List.of(); }
            });
            context.setServletContext(new MockServletContext()); context.register(WebConfig.class); context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new SaTokenContextFilterForJakartaServlet()).build();
            assertThatThrownBy(() -> mvc.perform(post("/system/dept/100/sync-children")))
                .hasRootCauseInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
            String noPermission=mvc.perform(post("/test/login/reader")).andReturn().getResponse().getContentAsString();
            assertThatThrownBy(() -> mvc.perform(post("/system/dept/100/sync-children").header("dept-test-token",noPermission)))
                .hasRootCauseInstanceOf(cn.dev33.satoken.exception.NotPermissionException.class);
            String sync=mvc.perform(post("/test/login/sync")).andReturn().getResponse().getContentAsString();
            mvc.perform(post("/system/dept/100/sync-children").header("dept-test-token",sync))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.subcompanyId").value("5"));
            verify(context.getBean(ISysDeptSyncService.class),times(1)).syncChildren(100L);
        } finally {
            SaManager.setConfig(oldConfig); SaManager.setSaTokenDao(oldDao); SaManager.setSaTokenContext(oldContext); SaManager.setStpInterface(oldPermissions);
            strategy.createSaRequest=oldRequest; strategy.createSaResponse=oldResponse; strategy.createSaStorage=oldStorage; strategy.routeMatcher=oldMatcher;
        }
    }
}
