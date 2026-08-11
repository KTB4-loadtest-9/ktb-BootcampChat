package com.ktb.chatapp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.PageMetadata;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RecentMessageCounter;
import com.ktb.chatapp.service.RoomService;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void getAllRooms_passesPaginationToService() {
        RoomsResponse expected = RoomsResponse.builder()
                .success(true)
                .data(List.of())
                .metadata(PageMetadata.builder().page(1).pageSize(20).build())
                .build();
        when(roomService.getAllRooms("user-1", 1, 20)).thenReturn(expected);

        ResponseEntity<?> response = controller().getAllRooms(principal("user-1"), 1, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(roomService).getAllRooms("user-1", 1, 20);
    }

    @Test
    void getAllRooms_rejectsPageSizeAboveMaximum() {
        ResponseEntity<?> response = controller().getAllRooms(principal("user-1"), 0, 51);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(com.ktb.chatapp.dto.StandardResponse.class);
        verifyNoInteractions(roomService);
    }

    @Test
    void getAllRooms_rejectsNegativePage() {
        ResponseEntity<?> response = controller().getAllRooms(principal("user-1"), -1, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(roomService, never()).getAllRooms("user-1", -1, 20);
    }

    private RoomController controller() {
        return new RoomController(userRepository, recentMessageCounter, roomService);
    }

    private Principal principal(String name) {
        return () -> name;
    }
}
