package com.aizuda.snailjob.server.starter.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ActuatorAuthFilterTest {

    /**
     * 验证缺少监控凭据时拒绝访问，而非接受空配置或抛出空指针异常。
     */
    @Test
    void shouldRejectMissingCredentials() throws Exception {
        for (String missing : new String[]{null, "", " "}) {
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((missing + ":" + missing).getBytes(StandardCharsets.UTF_8)));
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            new ActuatorAuthFilter(missing, missing).doFilter(request, response, chain);

            assertEquals(401, response.getStatus());
            verifyNoInteractions(chain);
        }
    }

    /**
     * 验证配置有效凭据后只有正确的认证头能够通过监控过滤器。
     */
    @Test
    void shouldRequireConfiguredCredentials() throws Exception {
        var filter = new ActuatorAuthFilter("monitor", "test-password");
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);

        request.addHeader("Authorization", "Basic " + Base64.getEncoder()
            .encodeToString("monitor:test-password".getBytes(StandardCharsets.UTF_8)));
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }
}
