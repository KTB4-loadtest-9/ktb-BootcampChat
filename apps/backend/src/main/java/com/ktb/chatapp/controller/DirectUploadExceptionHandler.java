package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.StandardResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@RestControllerAdvice(assignableTypes = {DirectChatImageController.class, DirectProfileImageController.class})
@ConditionalOnProperty(name = "file.direct-upload.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DirectUploadExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<StandardResponse<Object>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(StandardResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<StandardResponse<Object>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(409).body(StandardResponse.error(exception.getMessage()));
    }
}
