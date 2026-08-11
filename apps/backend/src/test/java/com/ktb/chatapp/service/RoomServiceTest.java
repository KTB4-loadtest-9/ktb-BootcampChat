package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.CreateRoomRequest;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        Pageable requestedPage = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id")));
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
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").isAscending()).isTrue();
        verify(userRepository).findAllById(eq(new LinkedHashSet<>(List.of("creator-1", "participant-1"))));
        verify(userRepository, never()).findById(any());
        verify(recentMessageCounter).countRecentMessagesByRoomIds(eq(List.of("room-1")));
    }

    @Test
    void getAllRooms_clampsPageSizeToMaximum() {
        Pageable requestedPage = PageRequest.of(
                0,
                RoomService.MAX_PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id")));
        when(roomRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), requestedPage, 0));

        RoomsResponse response = service().getAllRooms("viewer@example.com", 0, 100);

        assertThat(response.getMetadata().getPageSize()).isEqualTo(RoomService.MAX_PAGE_SIZE);
        verify(roomRepository).findAll(requestedPage);
    }

    @Test
    void getAllRooms_usesDeterministicSortAcrossAdjacentPages() {
        Room first = room("room-1");
        Room second = room("room-2");
        Room third = room("room-3");
        when(roomRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            List<Room> page = pageable.getPageNumber() == 0
                    ? List.of(first, second)
                    : List.of(third);
            return new PageImpl<>(page, pageable, 3);
        });
        when(recentMessageCounter.countRecentMessagesByRoomIds(any())).thenReturn(Map.of());

        RoomsResponse firstPage = service().getAllRooms("viewer@example.com", 0, 2);
        RoomsResponse secondPage = service().getAllRooms("viewer@example.com", 1, 2);

        assertThat(firstPage.getData()).extracting(RoomResponse::getId)
                .containsExactly("room-1", "room-2");
        assertThat(secondPage.getData()).extracting(RoomResponse::getId)
                .containsExactly("room-3");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(roomRepository, times(2)).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getAllValues()).extracting(Pageable::getPageNumber)
                .containsExactly(0, 1);
        assertThat(pageableCaptor.getAllValues()).allSatisfy(pageable -> {
            assertThat(pageable.getSort().getOrderFor("createdAt").isDescending()).isTrue();
            assertThat(pageable.getSort().getOrderFor("id").isAscending()).isTrue();
        });
    }

    @Test
    void getAllRooms_preservesMissingRelatedDataAsNullEmptyAndZero() {
        Room room = room("room-1", "missing-user");

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

    @Test
    void createRoom_usesKnownCreatorWithoutReloadingNewRoomRelations() {
        User creator = User.builder()
                .id("creator-1")
                .name("Creator")
                .email("creator@example.com")
                .build();
        when(userRepository.findByEmail("creator@example.com")).thenReturn(java.util.Optional.of(creator));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            room.setId("room-1");
            return room;
        });

        RoomResponse response = service().createRoomResponse(
                CreateRoomRequest.builder().name("room").build(),
                "creator@example.com");

        assertThat(response.getId()).isEqualTo("room-1");
        assertThat(response.getCreator().getId()).isEqualTo("creator-1");
        verify(userRepository, never()).findAllById(any());
        verify(recentMessageCounter, never()).countRecentMessages(anyString());
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

    private static Room room(String id) {
        return room(id, "creator-" + id);
    }

    private static Room room(String id, String creator) {
        return Room.builder()
                .id(id)
                .name(id)
                .creator(creator)
                .participantIds(Set.of(creator))
                .createdAt(LocalDateTime.of(2026, 8, 11, 9, 0))
                .build();
    }
}
