package com.ktb.chatapp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.PageMetadata;
import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RecentMessageCounter;
import com.ktb.chatapp.service.RoomService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class RoomControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RecentMessageCounter recentMessageCounter;

    @Mock
    private RoomService roomService;

    @Mock
    private Principal principal;

    private RoomController controller;

    @BeforeEach
    void setUp() {
        controller = new RoomController(userRepository, recentMessageCounter, roomService);
    }

    @Test
    void getAllRooms_passesPaginationToService() {
        RoomsResponse expected = RoomsResponse.builder()
                .success(true)
                .data(List.of())
                .metadata(PageMetadata.builder().page(1).pageSize(20).build())
                .build();
        when(roomService.getAllRooms("user-1", 1, 20)).thenReturn(expected);

        ResponseEntity<?> response = controller.getAllRooms(principal("user-1"), 1, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(roomService).getAllRooms("user-1", 1, 20);
    }

    @Test
    void getAllRooms_doesNotLetBrowsersReuseStaleRoomLists() {
        when(roomService.getAllRooms("user-1", 0, 10)).thenReturn(RoomsResponse.builder()
                .success(true)
                .data(List.of())
                .build());

        ResponseEntity<?> response = controller.getAllRooms(principal("user-1"), 0, 10);

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Last-Modified")).isNull();
    }

    @Test
    void getAllRooms_rejectsPageSizeAboveMaximum() {
        ResponseEntity<?> response = controller.getAllRooms(principal("user-1"), 0, 51);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(com.ktb.chatapp.dto.StandardResponse.class);
        verifyNoInteractions(roomService);
    }

    @Test
    void getAllRooms_rejectsNegativePage() {
        ResponseEntity<?> response = controller.getAllRooms(principal("user-1"), -1, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(roomService, never()).getAllRooms("user-1", -1, 20);
    }

    @Test
    void getRoomById_batchesCreatorAndParticipantLookup() {
        User creator = User.builder().id("creator").name("Creator").email("creator@example.com").build();
        User participant = User.builder().id("participant").name("Participant").email("participant@example.com").build();
        Room room = Room.builder()
                .id("room-1")
                .name("room")
                .creator("creator")
                .participantIds(Set.of("creator", "participant"))
                .createdAt(LocalDateTime.now())
                .build();

        when(principal.getName()).thenReturn("creator");
        when(roomService.findRoomById("room-1")).thenReturn(Optional.of(room));
        when(userRepository.findAllById(any())).thenReturn(List.of(creator, participant));
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(0);

        ResponseEntity<?> response = controller.getRoomById("room-1", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RoomResponse roomResponse = (RoomResponse) ((Map<?, ?>) response.getBody()).get("data");
        assertThat(roomResponse.getParticipants())
                .extracting(participantResponse -> participantResponse.getId())
                .containsExactlyInAnyOrder("creator", "participant");
        ArgumentCaptor<Iterable<String>> userIdsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository).findAllById(userIdsCaptor.capture());
        Set<String> queriedUserIds = new HashSet<>();
        userIdsCaptor.getValue().forEach(queriedUserIds::add);
        assertThat(queriedUserIds).contains("creator", "participant");
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    void createRoom_usesServiceResponseWithoutRemapping() {
        RoomResponse roomResponse = RoomResponse.builder().id("room-1").build();
        when(principal.getName()).thenReturn("creator");
        when(roomService.createRoomResponse(any(), eq("creator"))).thenReturn(roomResponse);

        ResponseEntity<?> response = controller.createRoom(
                com.ktb.chatapp.dto.CreateRoomRequest.builder().name("room").build(), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(roomService).createRoomResponse(any(), eq("creator"));
        verify(userRepository, never()).findAllById(any());
    }

    private Principal principal(String name) {
        return () -> name;
    }
}
