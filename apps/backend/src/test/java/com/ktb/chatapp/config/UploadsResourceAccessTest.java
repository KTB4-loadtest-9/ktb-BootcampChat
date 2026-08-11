package com.ktb.chatapp.config;

import com.ktb.chatapp.controller.ProfileImageController;
import com.ktb.chatapp.security.CustomBearerTokenResolver;
import com.ktb.chatapp.security.SessionAwareJwtAuthenticationConverter;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.storage.LocalStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 업로드 디렉토리가 익명에게 노출되지 않는지 고정한다.
 *
 * <p>{@link SecurityConfigTest}와 달리 실제 서빙 컨트롤러({@link ProfileImageController})와 실제
 * 스토리지({@link LocalStorage})를 슬라이스에 넣는다. 인증 필터 통과 여부만이 아니라 어떤 파일까지 실제로
 * 바이트가 나가는지가 검증 대상이기 때문이다.
 *
 * <p>프로필과 채팅 이미지 모두 서명 URL 또는 인증된 fallback 경로를 사용한다.
 */
@WebMvcTest(controllers = {ProfileImageController.class, UploadsResourceAccessTest.NoopController.class})
@Import({SecurityConfig.class, WebMvcConfig.class, LocalStorage.class})
@TestPropertySource(properties = "file.upload-dir=target/test-classes/test-uploads")
class UploadsResourceAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CustomBearerTokenResolver bearerTokenResolver;

    @MockitoBean
    private SessionAwareJwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    void profileImageIsNotServedToAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/files/profiles/avatar.png"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 채팅 첨부는 {@code chat/} key로 저장된다({@code LocalFileService.uploadFile}). 익명에게 닿는 경로가
     * 하나라도 생기면 {@code /api/files/view}의 3홉 인가를 우회하는 두 번째 경로가 된다. 인가 경로가 막는지와
     * 공개 서빙 경로가 프로필 밖으로 새지 않는지를 함께 확인한다.
     */
    @Test
    void chatAttachmentIsNotServedToAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/files/view/chat-attachment.jpg"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/files/profiles/chat-attachment.jpg"))
                .andExpect(status().is4xxClientError());
    }

    @RestController
    static class NoopController {

        @GetMapping("/api/noop")
        String noop() {
            return "ok";
        }
    }
}
