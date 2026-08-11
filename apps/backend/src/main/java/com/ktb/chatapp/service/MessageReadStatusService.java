package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MessageRepository messageRepository;

    /**
     * 메시지 읽음 상태 업데이트
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     * @param roomId 읽음 상태를 업데이트할 방 ID
     */
    public void updateReadStatus(List<String> messageIds, String userId, String roomId) {
        if (messageIds == null || messageIds.isEmpty() || userId == null || roomId == null) {
            return;
        }

        List<String> uniqueMessageIds = messageIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (uniqueMessageIds.isEmpty()) {
            return;
        }

        try {
            messageRepository.addReaderToMessages(
                    uniqueMessageIds,
                    roomId,
                    userId,
                    LocalDateTime.now());
            log.debug("Read status updated for {} messages by user {}",
                    uniqueMessageIds.size(), userId);
        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
        }
    }
}
