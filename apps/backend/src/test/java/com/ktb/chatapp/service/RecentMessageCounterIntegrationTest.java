package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@Testcontainers
class RecentMessageCounterIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.3.4");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private RecentMessageCounter counter;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "recent-message-counter-test");
        counter = new RecentMessageCounter(mock(MessageRepository.class), mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection(Message.class);
        mongoClient.close();
    }

    @Test
    void countRecentMessagesByRoomIds_groupsRecentMessagesAndOmitsRoomsWithoutMatches() {
        saveMessage("room-1", LocalDateTime.now().minusMinutes(5));
        saveMessage("room-1", LocalDateTime.now().minusMinutes(10));
        saveMessage("room-1", LocalDateTime.now().minusMinutes(31));
        saveMessage("room-2", LocalDateTime.now().minusMinutes(1));

        var counts = counter.countRecentMessagesByRoomIds(
                List.of("room-1", "room-2", "room-3"));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(
                Map.of("room-1", 2, "room-2", 1));
    }

    private void saveMessage(String roomId, LocalDateTime timestamp) {
        mongoTemplate.save(Message.builder()
                .roomId(roomId)
                .content("message")
                .timestamp(timestamp)
                .build());
    }
}
