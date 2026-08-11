package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class RecentMessageCounterIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private RecentMessageCounter recentMessageCounter;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
    }

    @Test
    void countRecentMessagesByRoomIds_groupsOnlyRecentMessagesAcrossRequestedRooms() {
        LocalDateTime now = LocalDateTime.now();
        saveMessage("message-1", "room-1", now.minusMinutes(5));
        saveMessage("message-2", "room-1", now.minusMinutes(10));
        saveMessage("message-3", "room-1", now.minusMinutes(31));
        saveMessage("message-4", "room-2", now.minusMinutes(2));
        saveMessage("message-5", "room-3", now.minusMinutes(1));

        Map<String, Integer> counts = recentMessageCounter
                .countRecentMessagesByRoomIds(List.of("room-1", "room-2"));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("room-1", 2, "room-2", 1));
    }

    private void saveMessage(String id, String roomId, LocalDateTime timestamp) {
        Message message = Message.builder()
                .id(id)
                .roomId(roomId)
                .senderId("user-1")
                .content("message")
                .timestamp(timestamp)
                .build();
        messageRepository.save(message);
    }
}
