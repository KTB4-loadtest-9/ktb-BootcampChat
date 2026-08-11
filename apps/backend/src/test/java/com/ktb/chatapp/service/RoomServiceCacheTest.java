package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.CreateRoomRequest;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {RoomService.class, RoomServiceCacheTest.TestCacheConfig.class})
class RoomServiceCacheTest {

    @Autowired private RoomService roomService;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private RoomRepository roomRepository;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private RecentMessageCounter recentMessageCounter;
    @MockitoBean private PasswordEncoder passwordEncoder;
    @MockitoBean private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("rooms").clear();
    }

    @Test
    void identicalRoomListRequestQueriesRepositoryOnce() {
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        roomService.getAllRooms("viewer@example.com", 0, 10);
        roomService.getAllRooms("viewer@example.com", 0, 10);

        verify(roomRepository, times(1)).findAll(any(Pageable.class));
    }

    @Test
    void differentUsersDoNotShareRoomListCache() {
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        roomService.getAllRooms("first@example.com", 0, 10);
        roomService.getAllRooms("second@example.com", 0, 10);

        verify(roomRepository, times(2)).findAll(any(Pageable.class));
    }

    @Test
    void differentPagesDoNotShareRoomListCache() {
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        roomService.getAllRooms("viewer@example.com", 0, 10);
        roomService.getAllRooms("viewer@example.com", 1, 10);

        verify(roomRepository, times(2)).findAll(any(Pageable.class));
    }

    @Test
    void differentPageSizesDoNotShareRoomListCache() {
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        roomService.getAllRooms("viewer@example.com", 0, 5);
        roomService.getAllRooms("viewer@example.com", 0, 10);

        verify(roomRepository, times(2)).findAll(any(Pageable.class));
    }

    @Test
    void failedRoomListResponseIsNotCached() {
        when(roomRepository.findAll(any(Pageable.class)))
            .thenThrow(new RuntimeException("temporary failure"))
            .thenReturn(new PageImpl<>(List.of()));

        RoomsResponse failed = roomService.getAllRooms("viewer@example.com", 0, 10);
        RoomsResponse recovered = roomService.getAllRooms("viewer@example.com", 0, 10);

        assertThat(failed.isSuccess()).isFalse();
        assertThat(recovered.isSuccess()).isTrue();
        verify(roomRepository, times(2)).findAll(any(Pageable.class));
    }

    @Test
    void creatingRoomEvictsRoomListCache() {
        User creator = user("user-1", "creator@example.com");
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(userRepository.findByEmail(creator.getEmail())).thenReturn(Optional.of(creator));
        when(userRepository.findAllById(any())).thenReturn(List.of(creator));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            room.setId("room-1");
            return room;
        });

        roomService.getAllRooms("viewer@example.com", 0, 10);
        roomService.createRoom(CreateRoomRequest.builder().name("new room").build(), creator.getEmail());
        roomService.getAllRooms("viewer@example.com", 0, 10);

        verify(roomRepository, times(2)).findAll(any(Pageable.class));
    }

    @Test
    void joiningRoomEvictsRoomListCache() {
        User creator = user("user-1", "creator@example.com");
        User joiner = user("user-2", "joiner@example.com");
        Room room = Room.builder()
            .id("room-1")
            .name("room")
            .creator(creator.getId())
            .participantIds(new HashSet<>(Set.of(creator.getId())))
            .build();
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(userRepository.findByEmail(joiner.getEmail())).thenReturn(Optional.of(joiner));
        when(userRepository.findAllById(any())).thenReturn(List.of(creator, joiner));
        doAnswer(invocation -> room.getParticipantIds().add(joiner.getId()))
            .when(roomRepository).addParticipant(room.getId(), joiner.getId());

        roomService.getAllRooms("viewer@example.com", 0, 10);
        roomService.joinRoom(room.getId(), null, joiner.getEmail());
        roomService.getAllRooms("viewer@example.com", 0, 10);

        verify(roomRepository, times(2)).findAll(any(Pageable.class));
    }

    @Test
    void responseMutationsEvictRoomListCache() {
        User creator = user("user-1", "creator@example.com");
        User joiner = user("user-2", "joiner@example.com");
        Room room = Room.builder()
            .id("room-1")
            .name("room")
            .creator(creator.getId())
            .participantIds(new HashSet<>(Set.of(creator.getId())))
            .build();
        when(roomRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(userRepository.findByEmail(creator.getEmail())).thenReturn(Optional.of(creator));
        when(userRepository.findByEmail(joiner.getEmail())).thenReturn(Optional.of(joiner));
        when(userRepository.findAllById(any())).thenReturn(List.of(creator, joiner));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room savedRoom = invocation.getArgument(0);
            if (savedRoom.getId() == null) {
                savedRoom.setId("room-1");
            }
            return savedRoom;
        });
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        doAnswer(invocation -> room.getParticipantIds().add(joiner.getId()))
            .when(roomRepository).addParticipant(room.getId(), joiner.getId());

        roomService.getAllRooms("viewer@example.com", 0, 10);
        roomService.createRoomResponse(CreateRoomRequest.builder().name("new room").build(), creator.getEmail());
        roomService.getAllRooms("viewer@example.com", 0, 10);
        roomService.joinRoomResponse(room.getId(), null, joiner.getEmail());
        roomService.getAllRooms("viewer@example.com", 0, 10);

        verify(roomRepository, times(3)).findAll(any(Pageable.class));
    }

    private static User user(String id, String email) {
        return User.builder()
            .id(id)
            .name(id)
            .email(email)
            .build();
    }

    @Configuration
    @EnableCaching
    static class TestCacheConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("rooms");
        }
    }
}
