package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RecentMessageCounter recentMessageCounter;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void getAllRooms_pageEnrichment_usesPagedRoomsAndBatchQueries() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 11, 9, 0);
        Room room = Room.builder()
                .id("room-1")
                .name("방 1")
                .creator("creator-1")
                .createdAt(createdAt)
                .participantIds(new LinkedHashSet<>(List.of("creator-1", "participant-1")))
                .build();
        Pageable requestedPage = org.springframework.data.domain.PageRequest.of(
                1,
                2,
                org.springframework.data.domain.Sort.by("createdAt").descending()
        );
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(room), requestedPage, 3));
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(
                        User.builder().id("creator-1").name("생성자").email("creator@example.com").build(),
                        User.builder().id("participant-1").name("참여자").email("participant@example.com").build()
                ));
        when(recentMessageCounter.countRecentMessagesByRoomIds(any()))
                .thenReturn(Map.of("room-1", 4));

        RoomsResponse response = service().getAllRooms("creator-1", 1, 2);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().getFirst().getName()).isEqualTo("방 1");
        assertThat(response.getData().getFirst().getParticipants()).extracting("id")
                .containsExactly("creator-1", "participant-1");
        assertThat(response.getData().getFirst().getRecentMessageCount()).isEqualTo(4);
        assertThat(response.getMetadata()).satisfies(metadata -> {
            assertThat(metadata.getTotal()).isEqualTo(3);
            assertThat(metadata.getPage()).isEqualTo(1);
            assertThat(metadata.getPageSize()).isEqualTo(2);
            assertThat(metadata.getTotalPages()).isEqualTo(2);
            assertThat(metadata.isHasMore()).isFalse();
            assertThat(metadata.getCurrentCount()).isEqualTo(1);
        });

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(roomRepository).findAll(pageableCaptor.capture());
        verify(roomRepository, never()).findAll();
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        verify(userRepository).findAllById(eq(new LinkedHashSet<>(List.of("creator-1", "participant-1"))));
        verify(userRepository, never()).findById(any());
        verify(recentMessageCounter).countRecentMessagesByRoomIds(eq(List.of("room-1")));
    }

    private RoomService service() {
        return new RoomService(
                roomRepository,
                userRepository,
                recentMessageCounter,
                passwordEncoder,
                eventPublisher
        );
    }
}
