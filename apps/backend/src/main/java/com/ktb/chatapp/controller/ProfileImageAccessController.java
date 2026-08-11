package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.ProfileImageAccessUrlsRequest;
import com.ktb.chatapp.dto.ProfileImageAccessUrlsResponse;
import com.ktb.chatapp.service.ProfileImageAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/profile-images")
@RequiredArgsConstructor
public class ProfileImageAccessController {
    private final ProfileImageAccessService service;

    @PostMapping("/access-urls")
    public ProfileImageAccessUrlsResponse accessUrls(@Valid @RequestBody ProfileImageAccessUrlsRequest request) {
        return service.accessUrls(request.userIds());
    }
}
