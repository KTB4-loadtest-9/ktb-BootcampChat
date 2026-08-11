package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RecentMessageCounter;
import com.ktb.chatapp.service.RoomService;
import java.security.Principal;
import java.util.HashSet;
import java.time.LocalDateTime;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private RoomService roomService;
    @Mock private Principal principal;

    private RoomController controller;

    @BeforeEach
    void setUp() {
        controller = new RoomController(userRepository, recentMessageCounter, roomService);
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
}
