package com.ktb.chatapp.websocket.socketio.ai;

import com.ktb.chatapp.cache.MessagePageCache;
import com.ktb.chatapp.event.AiMessageCompleteEvent;
import com.ktb.chatapp.model.AiType;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.websocket.socketio.handler.StreamingSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class AiServiceUnitTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MessageRepository messageRepository;
    @Mock private MessagePageCache messagePageCache;

    @Test
    void streamResponse_rejectsUnknownPersonaBeforeCallingOpenAi() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        AiService aiService = new AiService(
                chatClientBuilder, eventPublisher, messageRepository, messagePageCache);
        StreamingSession session = StreamingSession.builder()
                .messageId("ai-message-1")
                .roomId("room-1")
                .userId("user-1")
                .aiType("unknown")
                .query("hello")
                .timestamp(System.currentTimeMillis())
                .build();

        StepVerifier.create(aiService.streamResponse(session))
                .expectErrorMatches(error ->
                        error instanceof IllegalArgumentException
                                && error.getMessage().contains("Unknown AI persona"))
                .verify();
    }

    @Test
    void completedAiMessageInvalidatesRoomAfterMongoSave() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(messageRepository.save(any())).thenAnswer(invocation -> {
            var message = invocation.<com.ktb.chatapp.model.Message>getArgument(0);
            message.setId("saved-message-1");
            return message;
        });
        AiService aiService = new AiService(
                chatClientBuilder, eventPublisher, messageRepository, messagePageCache);
        AiMessageCompleteEvent event = new AiMessageCompleteEvent(
                this,
                "room-1",
                "ai-message-1",
                "answer",
                AiType.WAYNE_AI,
                System.currentTimeMillis(),
                "question",
                100L);

        aiService.onAiMessageCompleteEvent(event);

        verify(messagePageCache).invalidateRoom("room-1");
        verify(eventPublisher).publishEvent(any());
    }
}
