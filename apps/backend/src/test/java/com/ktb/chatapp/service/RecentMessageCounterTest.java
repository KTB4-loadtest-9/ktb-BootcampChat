package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.repository.MessageRepository;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

@ExtendWith(MockitoExtension.class)
class RecentMessageCounterTest {

    @Mock private MessageRepository messageRepository;
    @Mock private MongoOperations mongoOperations;

    @Test
    void countRecentMessagesByRoomIds_returnsCountsFromOneAggregation() {
        AggregationResults<Document> results = new AggregationResults<>(
                List.of(
                        new Document("_id", "room-1").append("count", 3),
                        new Document("_id", "room-2").append("count", 7)),
                new Document());
        when(mongoOperations.aggregate(any(Aggregation.class), eq("messages"), eq(Document.class)))
                .thenReturn(results);

        Map<String, Integer> counts = counter().countRecentMessagesByRoomIds(List.of("room-1", "room-2"));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("room-1", 3, "room-2", 7));
        verify(mongoOperations).aggregate(any(Aggregation.class), eq("messages"), eq(Document.class));
        verify(messageRepository, never()).countRecentMessagesByRoomId(any(), any());
    }

    @Test
    void countRecentMessagesByRoomIds_withNoRoomIds_returnsEmptyWithoutQuery() {
        assertThat(counter().countRecentMessagesByRoomIds(List.of())).isEmpty();

        verifyNoInteractions(mongoOperations, messageRepository);
    }

    private RecentMessageCounter counter() {
        return new RecentMessageCounter(messageRepository, mongoOperations);
    }
}
