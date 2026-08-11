package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Test
    void getAllRooms_bulkLoadsUsersAndRecentMessageCounts() {
        Room olderRoom = room("room-1", "user-1", "2026-08-10T10:00:00");
        Room newerRoom = room("room-2", "user-2", "2026-08-10T11:00:00");
        User firstUser = user("user-1", "first@example.com");
        User secondUser = user("user-2", "second@example.com");

        PageRequest pageRequest = PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newerRoom, olderRoom), pageRequest, 12));
        when(userRepository.findAllById(any())).thenReturn(List.of(firstUser, secondUser));
        when(recentMessageCounter.countRecentMessagesByRoomIds(any()))
                .thenReturn(Map.of("room-1", 3, "room-2", 7));

        RoomsResponse response = service().getAllRooms("first@example.com", 1, 100);

        assertThat(response.getData())
                .extracting(RoomResponse::getId)
                .containsExactly("room-2", "room-1");
        assertThat(response.getData())
                .extracting(RoomResponse::getRecentMessageCount)
                .containsExactly(7, 3);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMetadata().getTotal()).isEqualTo(12);
        assertThat(response.getMetadata().getPage()).isEqualTo(1);
        assertThat(response.getMetadata().getPageSize()).isEqualTo(10);
        assertThat(response.getMetadata().getTotalPages()).isEqualTo(2);
        assertThat(response.getMetadata().isHasMore()).isFalse();
        assertThat(response.getMetadata().getCurrentCount()).isEqualTo(2);
        assertThat(response.getData().getFirst().getCreator().getId()).isEqualTo("user-2");
        assertThat(response.getData().getFirst().getParticipants())
                .extracting(UserResponse::getId)
                .containsExactly("user-2");

        verify(userRepository).findAllById(Set.of("user-1", "user-2"));
        verify(userRepository, never()).findById(anyString());
        verify(recentMessageCounter).countRecentMessagesByRoomIds(List.of("room-2", "room-1"));
        verify(recentMessageCounter, never()).countRecentMessages(anyString());
        verify(roomRepository).findAll(pageRequest);
    }

    @Test
    void getAllRooms_preservesMissingRelatedDataAsNullEmptyAndZero() {
        Room room = room("room-1", "missing-user", "2026-08-10T10:00:00");

        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(room), PageRequest.of(0, 10), 1));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(recentMessageCounter.countRecentMessagesByRoomIds(any())).thenReturn(Map.of());

        RoomsResponse response = service().getAllRooms("viewer@example.com", 0, 10);

        RoomResponse roomResponse = response.getData().getFirst();
        assertThat(roomResponse.getCreator()).isNull();
        assertThat(roomResponse.getParticipants()).isEmpty();
        assertThat(roomResponse.getRecentMessageCount()).isZero();
    }

    @Test
    void getAllRooms_withNoRooms_doesNotQueryRelatedCollections() {
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        RoomsResponse response = service().getAllRooms("first@example.com", 0, 10);

        assertThat(response.getData()).isEmpty();
        verify(userRepository, never()).findAllById(any());
        verify(recentMessageCounter, never()).countRecentMessagesByRoomIds(any());
    }

    private RoomService service() {
        return new RoomService(roomRepository, userRepository, recentMessageCounter, passwordEncoder, eventPublisher);
    }

    private static Room room(String id, String creator, String createdAt) {
        return Room.builder()
                .id(id)
                .name(id)
                .creator(creator)
                .participantIds(Set.of(creator))
                .createdAt(LocalDateTime.parse(createdAt))
                .build();
    }

    private static User user(String id, String email) {
        return User.builder()
                .id(id)
                .email(email)
                .name(id)
                .build();
    }
}
