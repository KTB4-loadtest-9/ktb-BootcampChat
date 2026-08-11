package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RecentMessageCount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecentMessageCounterTest {

    @Mock
    private MessageRepository messageRepository;

    @Test
    void countRecentMessagesByRoomIds_emptyRoomIds_skipsDatabaseQueries() {
        RecentMessageCounter counter = new RecentMessageCounter(messageRepository);

        assertThat(counter.countRecentMessagesByRoomIds(List.of())).isEmpty();

        verifyNoInteractions(messageRepository);
    }

    @Test
    void countRecentMessagesByRoomIds_aggregatesAllRoomsInOneQuery() {
        when(messageRepository.countRecentMessagesByRoomIds(any(), any()))
                .thenReturn(List.of(
                        new RecentMessageCount("room-1", 3),
                        new RecentMessageCount("room-2", 1)
                ));
        RecentMessageCounter counter = new RecentMessageCounter(messageRepository);

        Map<String, Integer> counts = counter.countRecentMessagesByRoomIds(List.of("room-1", "room-2"));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("room-1", 3, "room-2", 1));
        verify(messageRepository).countRecentMessagesByRoomIds(any(), any());
        verify(messageRepository, never()).countRecentMessagesByRoomId(any(), any());
    }
}
