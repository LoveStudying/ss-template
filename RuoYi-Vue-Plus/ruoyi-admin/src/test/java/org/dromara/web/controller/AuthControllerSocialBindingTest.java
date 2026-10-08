package org.dromara.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.extra.spring.SpringUtil;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.social.config.properties.SocialProperties;
import org.dromara.common.social.utils.AuthRedisStateCache;
import org.dromara.common.social.utils.SocialUtils;
import org.dromara.system.api.MessageService;
import org.dromara.system.api.model.SocialLoginBody;
import org.dromara.system.service.ISysClientService;
import org.dromara.system.service.ISysConfigService;
import org.dromara.system.service.ISysSocialService;
import org.dromara.web.service.SysLoginService;
import org.dromara.web.service.SysRegisterService;
import org.dromara.web.config.properties.LoginProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticApplicationContext;

import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("三生账号绑定的本地用户边界测试")
class AuthControllerSocialBindingTest {

    private static StaticApplicationContext context;
    private static ValidatorFactory validatorFactory;

    @BeforeAll
    static void initializeValidation() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("validator", validatorFactory.getValidator());
        context.refresh();
        new SpringUtil().setApplicationContext(context);
    }

    @AfterAll
    static void releaseValidation() {
        context.close();
        validatorFactory.close();
    }

    @Mock private SocialProperties socialProperties;
    @Mock private AuthRedisStateCache authStateCache;
    @Mock private SysLoginService loginService;
    @Mock private SysRegisterService registerService;
    @Mock private ISysConfigService configService;
    @Mock private ISysSocialService socialUserService;
    @Mock private ISysClientService clientService;
    @Mock private ScheduledExecutorService scheduledExecutorService;
    @Mock private MessageService messageService;
    @Mock private LoginProperties loginProperties;
    @InjectMocks private AuthController controller;

    @Test
    @DisplayName("未登录用户仅获取公开登录模式")
    void exposesConfiguredLoginMode() {
        when(loginProperties.getLoginMode()).thenReturn("sso");
        var result = controller.loginConfig();
        assertEquals(200, result.getCode());
        assertEquals("sso", result.getData().getMode());
        verifyNoInteractions(socialProperties, authStateCache, loginService, clientService);
    }

    @Test
    @DisplayName("没有当前用户的绑定授权记录时拒绝绑定")
    void shouldRejectBindingWithoutUserBoundState() {
        SocialLoginBody body = bindingBody();
        try (var stp = mockStatic(StpUtil.class);
             var helper = mockStatic(LoginHelper.class);
             var social = mockStatic(SocialUtils.class)) {
            helper.when(LoginHelper::getUserId).thenReturn(2L);
            assertThrows(ServiceException.class, () -> controller.socialCallback(body));
            verifyNoInteractions(loginService);
            social.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("当前用户的一次有效授权允许绑定公司账号")
    void shouldBindAfterConsumingOwnedAuthorization() {
        SocialLoginBody body = bindingBody();
        AuthUser companyUser = AuthUser.builder().source("sso").uuid("test-ad-account").build();
        when(authStateCache.consumeBindingUser("test-state", 1L)).thenReturn(true);
        try (var stp = mockStatic(StpUtil.class);
             var helper = mockStatic(LoginHelper.class);
             var social = mockStatic(SocialUtils.class)) {
            helper.when(LoginHelper::getUserId).thenReturn(1L);
            social.when(() -> SocialUtils.loginAuth("sso", "test-code", "test-state", socialProperties))
                .thenReturn(AuthResponse.<AuthUser>builder().code(2000).data(companyUser).build());

            assertEquals(200, controller.socialCallback(body).getCode());
            verify(authStateCache).consumeBindingUser("test-state", 1L);
            verify(loginService).socialRegister(companyUser);
        }
    }

    @Test
    @DisplayName("绑定授权关联发起用户，普通登录授权不创建绑定记录")
    void shouldAssociateBindingAuthorizationWithCurrentUser() {
        AuthRequest request = mock(AuthRequest.class);
        when(request.authorize(anyString())).thenReturn("https://dev-login.3sbio.com/user/login");
        try (var stp = mockStatic(StpUtil.class);
             var helper = mockStatic(LoginHelper.class);
             var social = mockStatic(SocialUtils.class)) {
            helper.when(LoginHelper::getUserId).thenReturn(1L);
            social.when(() -> SocialUtils.getAuthRequest("sso", socialProperties)).thenReturn(request);

            assertEquals(200, controller.authBinding("sso", "binding").getCode());
            ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
            verify(request).authorize(state.capture());
            assertFalse(state.getValue().isBlank());
            verify(authStateCache).cacheBindingUser(state.getValue(), 1L);
            stp.verify(StpUtil::checkLogin);

            clearInvocations(authStateCache);
            assertEquals(200, controller.authBinding("sso", "login").getCode());
            verifyNoInteractions(authStateCache);
            assertThrows(ServiceException.class, () -> controller.authBinding("sso", "unknown"));
        }
    }

    private static SocialLoginBody bindingBody() {
        SocialLoginBody body = new SocialLoginBody();
        body.setSource("sso");
        body.setSocialCode("test-code");
        body.setSocialState("test-state");
        body.setClientId("test-client");
        body.setGrantType("social");
        return body;
    }
}
