package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.service.FileUrl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 메시지를 응답 DTO로 변환하는 매퍼
 * 파일 정보, 사용자 정보 등을 포함한 MessageResponse 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageResponseMapper {

    private final FileRepository fileRepository;

    /**
     * Message 엔티티를 MessageResponse DTO로 변환
     *
     * @param message 변환할 메시지 엔티티
     * @param sender 메시지 발신자 정보 (null 가능)
     * @return MessageResponse DTO
     */
    public MessageResponse mapToMessageResponse(Message message, User sender) {
        return mapToMessageResponse(message, sender, null);
    }

    MessageResponse mapToMessageResponse(Message message, User sender, Map<String, File> filesById) {
        MessageResponse.MessageResponseBuilder builder = MessageResponse.builder()
                .id(message.getId())
                .content(message.getContent())
                .type(message.getType())
                .timestamp(message.toTimestampMillis())
                .roomId(message.getRoomId())
                .reactions(message.getReactions() != null ?
                        message.getReactions() : new HashMap<>())
                .readers(message.getReaders() != null ?
                        message.getReaders() : new ArrayList<>());

        // 발신자 정보 설정
        if (sender != null) {
            builder.sender(UserResponse.builder()
                    .id(sender.getId())
                    .name(sender.getName())
                    .email(sender.getEmail())
                    .profileImage(FileUrl.of(sender.getProfileImage()))
                    .build());
        }

        // 파일 정보 설정
        Optional.ofNullable(resolveFile(message.getFileId(), filesById))
                .map(file -> FileResponse.builder()
                        .id(file.getId())
                        .filename(file.getFilename())
                        .originalname(file.getOriginalname())
                        .mimetype(file.getMimetype())
                        .size(file.getSize())
                        .build())
                .ifPresent(builder::file);

        // 메타데이터 설정
        if (message.getMetadata() != null) {
            builder.metadata(message.getMetadata());
        }

        return builder.build();
    }

    Map<String, File> findFilesByIds(Collection<String> fileIds) {
        if (fileIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return fileRepository.findAllById(fileIds).stream()
                .collect(Collectors.toMap(File::getId, Function.identity()));
    }

    private File resolveFile(
            String fileId,
            Map<String, File> filesById) {
        if (fileId == null) {
            return null;
        }
        if (filesById != null) {
            return filesById.get(fileId);
        }
        return fileRepository.findById(fileId).orElse(null);
    }
}
