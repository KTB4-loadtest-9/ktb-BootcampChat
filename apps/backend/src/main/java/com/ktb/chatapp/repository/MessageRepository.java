package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
    Slice<Message> findByRoomIdAndTimestampBefore(String roomId, LocalDateTime timestamp, Pageable pageable);

    @Query("{ '_id': { '$in': ?0 }, 'room': ?1, 'readers.userId': { '$ne': ?2 } }")
    @Update("{ '$addToSet': { 'readers': { 'userId': ?2, 'readAt': ?3 } } }")
    void addReaderToMessages(List<String> messageIds, String roomId, String userId, LocalDateTime readAt);

    /**
     * 특정 시간 이후의 메시지 수 카운트
     * 최근 N분간 메시지 수를 조회할 때 사용
     */
    @Query(value = "{ 'room': ?0, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);

    /**
     * fileId로 메시지 조회 (파일 권한 검증용)
     */
    Optional<Message> findByFileId(String fileId);
}
