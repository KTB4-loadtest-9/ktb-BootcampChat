package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;
    private final MongoOperations mongoOperations;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    public Map<String, Integer> countRecentMessagesByRoomIds(Collection<String> roomIds) {
        var distinctRoomIds = roomIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (distinctRoomIds.isEmpty()) {
            return Map.of();
        }

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("room")
                        .in(distinctRoomIds)
                        .and("timestamp")
                        .gte(LocalDateTime.now().minus(RECENT_WINDOW))),
                Aggregation.group("room").count().as("count"));

        return mongoOperations.aggregate(aggregation, "messages", Document.class)
                .getMappedResults()
                .stream()
                .collect(Collectors.toMap(
                        document -> document.getString("_id"),
                        document -> ((Number) document.get("count")).intValue()));
    }
}
