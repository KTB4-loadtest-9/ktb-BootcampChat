package com.ktb.chatapp.service;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = "socketio.enabled=false")
class MessageReadStatusServiceIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageReadStatusService messageReadStatusService;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
    }

    @Test
    void updateReadStatus_updatesOnlyUnreadMessagesInTheRequestedRoom() {
        LocalDateTime previousReadAt = LocalDateTime.now().minusDays(1).withNano(0);
        messageRepository.save(message(
                "already-read",
                "room-1",
                List.of(reader("user-1", previousReadAt))));
        messageRepository.save(message("unread", "room-1", new ArrayList<>()));
        messageRepository.save(message("other-room", "room-2", new ArrayList<>()));

        messageReadStatusService.updateReadStatus(
                List.of("already-read", "unread", "unread", "other-room", "missing"),
                "user-1",
                "room-1");
        messageReadStatusService.updateReadStatus(List.of("unread"), "user-1", "room-1");

        Message alreadyRead = messageRepository.findById("already-read").orElseThrow();
        Message unread = messageRepository.findById("unread").orElseThrow();
        Message otherRoom = messageRepository.findById("other-room").orElseThrow();

        assertThat(alreadyRead.getReaders()).hasSize(1);
        assertThat(alreadyRead.getReaders().getFirst().getReadAt()).isEqualTo(previousReadAt);
        assertThat(unread.getReaders()).singleElement()
                .satisfies(reader -> assertThat(reader.getUserId()).isEqualTo("user-1"));
        assertThat(otherRoom.getReaders()).isEmpty();
    }

    private Message message(String id, String roomId, List<Message.MessageReader> readers) {
        return Message.builder()
                .id(id)
                .roomId(roomId)
                .content("message")
                .readers(readers)
                .build();
    }

    private Message.MessageReader reader(String userId, LocalDateTime readAt) {
        return Message.MessageReader.builder()
                .userId(userId)
                .readAt(readAt)
                .build();
    }
}
