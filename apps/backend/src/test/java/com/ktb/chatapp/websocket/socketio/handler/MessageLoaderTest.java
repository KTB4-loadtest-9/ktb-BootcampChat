package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.cache.MessagePageCache;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import net.datafaker.Faker;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageLoaderTest {
    
    @Mock
    private MessageRepository messageRepository;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private FileRepository fileRepository;
    
    @Mock
    private MessageReadStatusService messageReadStatusService;

    @Mock
    private MessagePageCache messagePageCache;
    
    @InjectMocks
    private MessageLoader messageLoader;
    
    private Faker faker;
    private List<Message> testMessages;
    private String roomId;
    private String userId;
    
    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
        userId = faker.internet().uuid();
        
        messageLoader = new MessageLoader(
                messageRepository,
                userRepository,
                new MessageResponseMapper(fileRepository),
                messageReadStatusService,
                messagePageCache
        );

        lenient().when(messagePageCache.getOrLoad(any(FetchMessagesRequest.class), any()))
                .thenAnswer(invocation -> new MessagePageCache.LoadResult(
                        ((Supplier<FetchMessagesResponse>) invocation.getArgument(1)).get(),
                        false));
        
        var testUser = User.builder()
                .id(userId)
                .name(faker.name().fullName())
                .email(faker.internet().emailAddress())
                .build();
        
        // 테스트 메시지 50개 생성 (오름차순: 오래된 것 → 최신 것)
        // i=0: 50시간 전, i=1: 49시간 전, ... i=49: 1시간 전
        testMessages = IntStream.range(0, 50)
                .mapToObj(i -> createMessage(
                        faker.internet().uuid(),
                        LocalDateTime.now().minusHours(50 - i)
                ))
                .toList();
        
        lenient().when(userRepository.findAllById(anySet()))
                .thenReturn(List.of(testUser));
        lenient().doNothing().when(messageReadStatusService).updateReadStatus(anyList(), anyString(), anyString());
    }
    
    private Message createMessage(String id, LocalDateTime timestamp) {
        Message message = new Message();
        message.setId(id);
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setContent(faker.lorem().sentence(10));
        message.setTimestamp(timestamp);
        return message;
    }
    
    @Test
    @DisplayName("loadMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[0~29] (50시간 전 ~ 21시간 전) - 오름차순 상태
        List<Message> first30Messages = testMessages.subList(0, 30);
        
        // DB는 DESC 정렬로 반환한다고 가정 (최신 것 먼저)
        // [21시간 전, 22시간 전, ..., 50시간 전]
        var messagePage = getMessageSlice(first30Messages, true);
        
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(messagePage);
        
        // When: 메시지 로드
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        // Then: 결과는 오름차순으로 정렬되어야 함
        assertThat(result.getMessages()).hasSize(30);
        assertThat(result.isHasMore()).isTrue();
        
        // 시간순 정렬 확인 (오름차순: 오래된 것 → 최신 것)
        // [50시간 전, 49시간 전, ..., 21시간 전]
        verifyAscending(result);
    }
    
    private static @NotNull Slice<Message> getMessageSlice(List<Message> first30Messages, boolean hasNext) {
        List<Message> messages = new ArrayList<>(first30Messages.reversed());

        Pageable pageable = PageRequest.of(0, 30, Sort.by("timestamp").descending());
        return new SliceImpl<>(messages, pageable, hasNext);
    }
    
    @Test
    @DisplayName("loadInitialMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadInitialMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[20~49] (30시간 전 ~ 1시간 전) - 최신 30개 메시지
        List<Message> last30Messages = testMessages.subList(20, 50);
        
        // DB는 DESC 정렬로 반환 (최신 것부터)
        // [1시간 전, 2시간 전, ..., 30시간 전]
        Slice<Message> messagePage = getMessageSlice(last30Messages, false);
        
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(messagePage);
        
        // When: 초기 메시지 로드
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);
        
        // Then: 결과는 오름차순으로 정렬되어야 함
        assertThat(result.getMessages()).hasSize(30);
        
        // 시간순 정렬 확인 (오름차순: 오래된 것 → 최신 것)
        // [30시간 전, 29시간 전, ..., 1시간 전]
        verifyAscending(result);
    }

    @Test
    @DisplayName("loadMessages: 파일 정보는 메시지별 조회 없이 batch 조회")
    void loadMessages_shouldBatchLoadFiles() {
        Message firstMessage = createMessage("message-1", LocalDateTime.now().minusMinutes(2));
        firstMessage.setFileId("file-1");
        Message secondMessage = createMessage("message-2", LocalDateTime.now().minusMinutes(1));
        secondMessage.setFileId("file-2");

        Pageable pageable = PageRequest.of(0, 30, Sort.by("timestamp").descending());
        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(secondMessage, firstMessage), pageable, false));
        when(fileRepository.findAllById(any())).thenReturn(List.of(
                File.builder().id("file-1").filename("one.txt").build(),
                File.builder().id("file-2").filename("two.txt").build()));

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages())
                .extracting(response -> response.getFile().getId())
                .containsExactly("file-1", "file-2");
        verify(fileRepository).findAllById(any());
        verify(fileRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("loadMessages: senderId를 중복 제거해 배치 조회하고 없는 sender는 비워 둔다")
    void loadMessages_shouldBatchLoadDistinctSendersAndKeepMissingSendersNull() {
        Message first = createMessage("first", LocalDateTime.now().minusHours(2));
        Message duplicateSender = createMessage("duplicate", LocalDateTime.now().minusHours(1));
        Message aiMessage = createMessage("ai", LocalDateTime.now().minusMinutes(30));
        Message missingSender = createMessage("missing", LocalDateTime.now().minusMinutes(15));
        duplicateSender.setSenderId(userId);
        aiMessage.setSenderId(null);
        missingSender.setSenderId("missing-user");

        when(messageRepository.findByRoomIdAndTimestampBefore(
                eq(roomId), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(missingSender, aiMessage, duplicateSender, first),
                        PageRequest.of(0, 30, Sort.by("timestamp").descending()), false));
        when(userRepository.findAllById(Set.of(userId, "missing-user")))
                .thenReturn(List.of(User.builder().id(userId).name("sender").build()));

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result.getMessages()).hasSize(4);
        assertThat(result.getMessages().get(0).getSender().getId()).isEqualTo(userId);
        assertThat(result.getMessages().get(1).getSender().getId()).isEqualTo(userId);
        assertThat(result.getMessages().get(2).getSender()).isNull();
        assertThat(result.getMessages().get(3).getSender()).isNull();
        verify(userRepository).findAllById(Set.of(userId, "missing-user"));
        verify(userRepository, never()).findById(anyString());
    }
    
    private static void verifyAscending(FetchMessagesResponse result) {
        for (int i = 0; i < result.getMessages().size() - 1; i++) {
            long current = result.getMessages().get(i).getTimestamp();
            long next = result.getMessages().get(i + 1).getTimestamp();
            assertThat(current).isLessThanOrEqualTo(next);
        }
    }
    
    @Test
    @DisplayName("loadInitialMessages: 에러를 호출자에게 전달")
    void loadInitialMessages_shouldPropagateError() {
        when(messageRepository.findByRoomIdAndTimestampBefore(
                any(), any(LocalDateTime.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB error"));

        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);

        assertThatThrownBy(() -> messageLoader.loadMessages(req, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB error");
    }

    @Test
    @DisplayName("캐시 hit이면 MongoDB와 사용자/파일 조회 없이 읽음 상태만 갱신한다")
    void cacheHit_shouldSkipMongoAndUpdateReadStatus() {
        FetchMessagesResponse cached = FetchMessagesResponse.builder()
                .messages(List.of(com.ktb.chatapp.dto.MessageResponse.builder()
                        .id("cached-message")
                        .build()))
                .hasMore(false)
                .build();
        when(messagePageCache.getOrLoad(any(FetchMessagesRequest.class), any()))
                .thenReturn(new MessagePageCache.LoadResult(cached, true));

        FetchMessagesResponse result = messageLoader.loadMessages(
                new FetchMessagesRequest(roomId, 30, null), userId);

        assertThat(result).isEqualTo(cached);
        verifyNoInteractions(messageRepository, userRepository, fileRepository);
        verify(messageReadStatusService).updateReadStatus(
                List.of("cached-message"), userId, roomId);
    }
}
