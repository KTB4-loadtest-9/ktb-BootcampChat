package com.ktb.chatapp.websocket.socketio.handler;


import com.ktb.chatapp.cache.MessagePageCache;

import com.mongodb.ExplainVerbosity;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class MessageLoaderIntegrationTest {

    @MockitoSpyBean
    private MessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private MessagePageCache messagePageCache;

    @MockitoSpyBean
    private MessageReadStatusService messageReadStatusService;

    private MessageLoader messageLoader;
    private Faker faker;
    private String roomId;
    private String userId;
    private LocalDateTime baseTime;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
        userId = faker.internet().uuid();
        baseTime = LocalDateTime.now().minusHours(1);

        // MessageLoader 인스턴스 생성
        messageLoader = new MessageLoader(
                messageRepository,
                userRepository,
                new MessageResponseMapper(fileRepository),
                messageReadStatusService,
                messagePageCache
        );

        // 테스트 사용자 생성 및 저장
        User testUser = User.builder()
                .id(userId)
                .name(faker.name().fullName())
                .email(faker.internet().emailAddress())
                .build();
        userRepository.save(testUser);

        // MessageReadStatusService mock 설정
        doNothing().when(messageReadStatusService).updateReadStatus(anyList(), anyString(), anyString());
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("100개 메시지 생성 후 초기 30개, 이후 30개씩, 마지막 10개 순차적으로 로드")
    void loadMessages_shouldLoadInPagesOf30ThenFinal10() {
        // Given: 100개의 메시지 생성
        List<Message> messages = IntStream.range(0, 100)
                .mapToObj(this::createAndSaveMessage)
                .toList();

        // When & Then 1: 초기 30개 메시지 로드
        FetchMessagesRequest initialRequest = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse firstResponse = messageLoader.loadMessages(initialRequest, userId);

        assertThat(firstResponse.getMessages()).hasSize(30);
        assertThat(firstResponse.isHasMore()).isTrue();

        // 첫 번째 배치가 가장 오래된 30개 메시지인지 확인
        verifyMessageOrder(firstResponse);

        // When & Then 2: 두 번째 30개 메시지 로드 (before 파라미터 사용)
        long beforeSecond = firstResponse.firstMessageTimestamp();
        FetchMessagesRequest secondRequest = new FetchMessagesRequest(roomId, 30, beforeSecond);
        FetchMessagesResponse secondResponse = messageLoader.loadMessages(secondRequest, userId);

        assertThat(secondResponse.getMessages()).hasSize(30);
        assertThat(secondResponse.isHasMore()).isTrue();

        // 두 번째 배치가 그 다음 30개 메시지인지 확인
        verifyMessageOrder(secondResponse);

        // When & Then 3: 세 번째 30개 메시지 로드
        long beforeThird = secondResponse.firstMessageTimestamp();
        FetchMessagesRequest thirdRequest = new FetchMessagesRequest(roomId, 30, beforeThird);
        FetchMessagesResponse thirdResponse = messageLoader.loadMessages(thirdRequest, userId);

        assertThat(thirdResponse.getMessages()).hasSize(30);
        assertThat(thirdResponse.isHasMore()).isTrue();

        verifyMessageOrder(thirdResponse);

        // When & Then 4: 마지막 10개 메시지 로드
        Long beforeFourth = thirdResponse.firstMessageTimestamp();
        FetchMessagesRequest fourthRequest = new FetchMessagesRequest(roomId, 30, beforeFourth);
        FetchMessagesResponse fourthResponse = messageLoader.loadMessages(fourthRequest, userId);

        assertThat(fourthResponse.getMessages()).hasSize(10);
        assertThat(fourthResponse.isHasMore()).isFalse();

        // 마지막 배치가 가장 최신 메시지인지 확인
        verifyMessageOrder(fourthResponse);

        // 전체 로드된 메시지 수 확인
        int totalLoaded = firstResponse.getMessages().size()
                + secondResponse.getMessages().size()
                + thirdResponse.getMessages().size()
                + fourthResponse.getMessages().size();
        assertThat(totalLoaded).isEqualTo(100);
    }

    @Test
    @DisplayName("메시지가 30개 미만일 때 hasMore가 false")
    void loadMessages_whenLessThan30Messages_hasMoreShouldBeFalse() {
        // Given: 20개의 메시지만 생성
        IntStream.range(0, 20)
                .forEach(this::createAndSaveMessage);

        // When: 초기 30개 요청
        FetchMessagesRequest request = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse response = messageLoader.loadMessages(request, userId);

        // Then: 20개만 반환되고 hasMore는 false
        assertThat(response.getMessages()).hasSize(20);
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("before 파라미터가 모든 메시지보다 오래된 경우 빈 결과 반환")
    void loadMessages_whenBeforeIsOlderThanAllMessages_shouldReturnEmpty() {
        // Given: 메시지 생성 (10시간 전부터 1시간 전까지)
        IntStream.range(0, 10)
                .forEach(this::createAndSaveMessage);

        // When: 모든 메시지보다 오래된 시간으로 요청
        LocalDateTime veryOldTime = LocalDateTime.now().minusHours(100);
        Long beforeEpoch = veryOldTime.toEpochSecond(java.time.ZoneOffset.UTC);
        FetchMessagesRequest request = new FetchMessagesRequest(roomId, 30, beforeEpoch);
        FetchMessagesResponse response = messageLoader.loadMessages(request, userId);

        // Then: 빈 결과 반환
        assertThat(response.getMessages()).isEmpty();
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("동일 페이지는 MongoDB를 한 번만 조회하고 방 버전 증가 후 다시 조회한다")
    void repeatedPageUsesCacheUntilRoomInvalidation() {
        IntStream.range(0, 3)
                .forEach(this::createAndSaveMessage);

        FetchMessagesRequest request = new FetchMessagesRequest(roomId, 30, null);

        FetchMessagesResponse first = messageLoader.loadMessages(request, userId);
        FetchMessagesResponse cached = messageLoader.loadMessages(request, userId);

        assertThat(cached).isEqualTo(first);
        verify(messageRepository, times(1)).findByRoomIdAndTimestampBefore(
                anyString(), any(LocalDateTime.class), any());

        messagePageCache.invalidateRoom(roomId);
        messageLoader.loadMessages(request, userId);

        verify(messageRepository, times(2)).findByRoomIdAndTimestampBefore(
                anyString(), any(LocalDateTime.class), any());
    }

    @Test
    @DisplayName("메시지 이력 조회용 room + timestamp 복합 인덱스가 생성된다")
    void messageHistoryIndex_shouldBeCreated() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(Message.class).getIndexInfo();

        assertThat(indexes).anyMatch(index -> index.getName().equals("room_timestamp_idx"));
    }

    @Test
    @DisplayName("파일로 메시지를 조회할 때 인덱스를 사용한다")
    void messageFileIndex_shouldBeCreatedAndUsed() {
        String fileId = faker.internet().uuid();
        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setFileId(fileId);
        messageRepository.save(message);

        List<IndexInfo> indexes = mongoTemplate.indexOps(Message.class).getIndexInfo();
        assertThat(indexes).anyMatch(index ->
                index.getName().equals("idx_messages_file")
                        && index.isIndexForFields(List.of("file"))
        );

        Document explain = mongoTemplate.getCollection("messages")
                .find(new Document("file", fileId))
                .limit(2)
                .explain(ExplainVerbosity.EXECUTION_STATS);
        Document executionStats = explain.get("executionStats", Document.class);

        assertThat(explain.toJson()).contains("\"stage\": \"IXSCAN\"");
        assertThat(executionStats.getInteger("totalKeysExamined")).isBetween(1, 2);
        assertThat(executionStats.getInteger("totalDocsExamined")).isBetween(1, 2);
    }

    /**
     * 커서 페이징은 timestamp에 strict less-than을 걸기 때문에 같은 시각의 메시지가
     * 배치 경계에 걸리면 건너뛴다. 순번마다 1초씩 벌려 경계를 결정적으로 만든다.
     * timestamp는 @CreatedDate라 최초 저장 때 현재 시각으로 덮어써지므로,
     * 저장 후 한 번 더 갱신한다.
     */
    private Message createAndSaveMessage(int sequence) {
        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setContent(faker.lorem().sentence(10));

        Message saved = messageRepository.save(message);
        saved.setTimestamp(baseTime.plusSeconds(sequence));
        return messageRepository.save(saved);
    }

    private void verifyMessageOrder(FetchMessagesResponse response) {
        List<Long> timestamps = response.getMessages().stream()
                .map(MessageResponse::getTimestamp)
                .toList();

        // 오름차순 정렬 확인 (오래된 것 → 최신 것)
        for (int i = 0; i < timestamps.size() - 1; i++) {
            assertThat(timestamps.get(i))
                    .withFailMessage("메시지가 오름차순으로 정렬되지 않았습니다: index %d", i)
                    .isLessThanOrEqualTo(timestamps.get(i + 1));
        }
    }
}
