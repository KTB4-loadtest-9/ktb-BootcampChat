package com.ktb.chatapp.config;

import com.ktb.chatapp.security.CustomBearerTokenResolver;
import com.ktb.chatapp.security.SessionAwareJwtAuthenticationConverter;
import com.ktb.chatapp.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CustomBearerTokenResolver bearerTokenResolver;

    @MockitoBean
    private SessionAwareJwtAuthenticationConverter jwtAuthenticationConverter;

    /** RateLimitInterceptor가 웹 슬라이스에 함께 올라오므로 그 의존성도 채워야 한다. */
    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    void profileImagesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/files/profiles/sample.png"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherApiEndpointsStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe")
        String probe() {
            return "ok";
        }
    }
}
