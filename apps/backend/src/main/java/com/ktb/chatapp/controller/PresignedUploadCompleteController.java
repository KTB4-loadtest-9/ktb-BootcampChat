package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.DirectUploadCompleteResponse;
import com.ktb.chatapp.dto.PresignedUploadCompleteRequest;
import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.DirectImageUploadService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presigned chat-image completion compatibility surface for existing E2E tests.
 * Image bytes are uploaded directly to S3; this endpoint only verifies and
 * commits the uploaded object's metadata.
 */
@RestController
@RequiredArgsConstructor
public class PresignedUploadCompleteController {
    private final DirectImageUploadService directImageUploadService;
    private final UserRepository userRepository;

    @PostMapping(value = "/api/files/upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> complete(
            @Valid @RequestBody PresignedUploadCompleteRequest request,
            Principal principal) {
        try {
            if (!PresignedUploadCompleteRequest.CHAT_IMAGE_TYPE.equals(request.uploadType())) {
                throw new IllegalArgumentException("지원하지 않는 presigned 업로드 유형입니다.");
            }
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));
            return ResponseEntity.ok(new DirectUploadCompleteResponse(
                    true,
                    directImageUploadService.completeChat(request.uploadId(), user.getId())
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(StandardResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(StandardResponse.error(e.getMessage()));
        }
    }
}
