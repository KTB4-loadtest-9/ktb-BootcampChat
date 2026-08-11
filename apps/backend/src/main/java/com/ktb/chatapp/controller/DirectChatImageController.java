package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.DirectUploadCompleteResponse;
import com.ktb.chatapp.dto.DirectUploadRequest;
import com.ktb.chatapp.dto.DirectUploadResponse;
import com.ktb.chatapp.dto.FileAccessUrlResponse;
import com.ktb.chatapp.dto.FileAccessUrlsRequest;
import com.ktb.chatapp.model.DirectUpload;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.DirectImageUploadService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files/chat-images")
@RequiredArgsConstructor
public class DirectChatImageController {
    private final DirectImageUploadService service;
    private final UserRepository users;

    @PostMapping("/presign")
    public DirectUploadResponse presign(@Valid @RequestBody DirectUploadRequest request, Principal principal) {
        User user = currentUser(principal);
        return service.initiate(user.getId(), DirectUpload.Purpose.CHAT_IMAGE,
                request.originalName(), request.contentType(), request.size());
    }

    @PostMapping("/{uploadId}/complete")
    public DirectUploadCompleteResponse complete(@PathVariable String uploadId, Principal principal) {
        return new DirectUploadCompleteResponse(true, service.completeChat(uploadId, currentUser(principal).getId()));
    }

    @PostMapping("/access-urls")
    public FileAccessUrlResponse accessUrls(@Valid @RequestBody FileAccessUrlsRequest request, Principal principal) {
        return service.accessUrls(request.fileIds(), currentUser(principal).getId());
    }

    private User currentUser(Principal principal) {
        return users.findByEmail(principal.getName()).orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
    }
}
