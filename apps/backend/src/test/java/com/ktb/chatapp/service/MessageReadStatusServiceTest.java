package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageReadStatusServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Test
    void updateReadStatus_deduplicatesIdsAndUsesOneBulkUpdate() {
        MessageReadStatusService service = new MessageReadStatusService(messageRepository);

        service.updateReadStatus(
                List.of("message-1", "message-1", "message-2"),
                "user-1",
                "room-1");

        ArgumentCaptor<List<String>> messageIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).addReaderToMessages(
                messageIdsCaptor.capture(),
                eq("room-1"),
                eq("user-1"),
                any(LocalDateTime.class));

        assertThat(messageIdsCaptor.getValue()).containsExactly("message-1", "message-2");
        verify(messageRepository, never()).findById(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void updateReadStatus_ignoresEmptyInput() {
        MessageReadStatusService service = new MessageReadStatusService(messageRepository);

        service.updateReadStatus(List.of(), "user-1", "room-1");

        verify(messageRepository, never()).addReaderToMessages(any(), any(), any(), any());
    }
}
