package com.ktb.chatapp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.DirectUploadCompleteResponse;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.PresignedUploadCompleteRequest;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.DirectImageUploadService;
import java.security.Principal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class PresignedUploadCompleteControllerTest {
    private final DirectImageUploadService service = mock(DirectImageUploadService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final Principal principal = () -> "user@example.com";
    private PresignedUploadCompleteController controller;

    @BeforeEach
    void setUp() {
        controller = new PresignedUploadCompleteController(service, users);
        when(users.findByEmail(principal.getName()))
                .thenReturn(Optional.of(User.builder().id("user-1").email(principal.getName()).build()));
    }

    @Test
    void completesWithLegacySuccessShape() {
        FileResponse file = FileResponse.builder().id("file-1")
                .filename("chat/images/user-1/image.png").build();
        when(service.completeChat("upload-1", "user-1")).thenReturn(file);

        ResponseEntity<?> response = controller.complete(
                new PresignedUploadCompleteRequest("upload-1", "PRESIGNED_CHAT_IMAGE"), principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(new DirectUploadCompleteResponse(true, file));
    }

    @Test
    void rejectsUnknownUploadType() {
        ResponseEntity<?> response = controller.complete(
                new PresignedUploadCompleteRequest("upload-1", "UNKNOWN"), principal);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
