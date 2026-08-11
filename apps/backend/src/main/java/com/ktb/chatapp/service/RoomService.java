package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RecentMessageCounter recentMessageCounter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public RoomsResponse getAllRooms(String name) {
        return getAllRooms(name, 0, DEFAULT_PAGE_SIZE);
    }

    @Cacheable(cacheNames = "rooms", unless = "#result == null || !#result.success")
    public RoomsResponse getAllRooms(String name, int page, int pageSize) {

        try {
            int safePage = Math.max(page, 0);
            int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
            Page<Room> roomPage = roomRepository.findAll(PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt")
                    .and(Sort.by(Sort.Direction.ASC, "id"))));
            List<Room> rooms = roomPage.getContent();
            if (rooms.isEmpty()) {
                return RoomsResponse.builder()
                    .success(true)
                    .data(List.of())
                    .metadata(PageMetadata.builder()
                        .total(roomPage.getTotalElements())
                        .page(safePage)
                        .pageSize(safeSize)
                        .totalPages(Math.max(roomPage.getTotalPages(), 1))
                        .hasMore(false)
                        .currentCount(0)
                        .build())
                    .build();
            }

            Map<String, User> usersById = findUsersById(rooms);
            Map<String, Integer> recentMessageCounts = recentMessageCounter.countRecentMessagesByRoomIds(
                rooms.stream()
                    .map(Room::getId)
                    .filter(Objects::nonNull)
                    .toList());

            List<RoomResponse> roomResponses = rooms.stream()
                .map(room -> mapToRoomResponse(room, name, usersById, recentMessageCounts))
                .collect(Collectors.toList());

            PageMetadata metadata = PageMetadata.builder()
                .total(roomPage.getTotalElements())
                .page(safePage)
                .pageSize(safeSize)
                .totalPages(Math.max(roomPage.getTotalPages(), 1))
                .hasMore(roomPage.hasNext())
                .currentCount(roomResponses.size())
                .build();

            return RoomsResponse.builder()
                .success(true)
                .data(roomResponses)
                .metadata(metadata)
                .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                .success(false)
                .data(List.of())
                .build();
        }
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(isMongoConnected)
                .latency(latency)
                .build());

            return HealthResponse.builder()
                .success(true)
                .services(services)
                .lastActivity(lastActivity)
                .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                .success(false)
                .services(new HashMap<>())
                .build();
        }
    }

    @CacheEvict(cacheNames = "rooms", allEntries = true)
    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        return createRoomOperation(createRoomRequest, name).room();
    }

    @CacheEvict(cacheNames = "rooms", allEntries = true)
    public RoomResponse createRoomResponse(CreateRoomRequest createRoomRequest, String name) {
        return createRoomOperation(createRoomRequest, name).response();
    }

    private RoomOperation createRoomOperation(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);

        RoomResponse roomResponse = buildRoomResponse(
            savedRoom,
            name,
            creator,
            List.of(creator),
            0);
        // Publish event for room created
        try {
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return new RoomOperation(savedRoom, roomResponse);
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    @CacheEvict(cacheNames = "rooms", allEntries = true)
    public Room joinRoom(String roomId, String password, String name) {
        RoomOperation operation = joinRoomOperation(roomId, password, name);
        return operation == null ? null : operation.room();
    }

    @CacheEvict(cacheNames = "rooms", allEntries = true)
    public RoomResponse joinRoomResponse(String roomId, String password, String name) {
        RoomOperation operation = joinRoomOperation(roomId, password, name);
        return operation == null ? null : operation.response();
    }

    private RoomOperation joinRoomOperation(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        Set<String> participantIds = room.getParticipantIds() == null ? Set.of() : room.getParticipantIds();
        if (!participantIds.contains(user.getId())) {
            roomRepository.addParticipant(roomId, user.getId());
            room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                return null;
            }
            if (room.getParticipantIds() == null || !room.getParticipantIds().contains(user.getId())) {
                throw new IllegalStateException("채팅방 참가 상태를 반영하지 못했습니다.");
            }
        }
        
        RoomResponse roomResponse = mapToRoomResponse(room, name);

        // Publish event for room updated
        try {
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return new RoomOperation(room, roomResponse);
    }

    private RoomResponse mapToRoomResponse(Room room, String name) {
        if (room == null) return null;

        Map<String, User> usersById = findUsersById(List.of(room));
        Map<String, Integer> recentMessageCounts = room.getId() == null
            ? Map.of()
            : Map.of(room.getId(), recentMessageCounter.countRecentMessages(room.getId()));
        return mapToRoomResponse(room, name, usersById, recentMessageCounts);
    }

    private RoomResponse mapToRoomResponse(
            Room room,
            String name,
            Map<String, User> usersById,
            Map<String, Integer> recentMessageCounts
    ) {
        if (room == null) return null;

        User creator = usersById.get(room.getCreator());
        List<User> participants = room.getParticipantIds() == null
                ? List.of()
                : room.getParticipantIds().stream()
                        .map(usersById::get)
                        .filter(user -> user != null)
                        .toList();

        return buildRoomResponse(
                room,
                name,
                creator,
                participants,
                recentMessageCounts.getOrDefault(room.getId(), 0)
        );
    }

    private RoomResponse buildRoomResponse(
            Room room,
            String name,
            User creator,
            List<User> participants,
            int recentMessageCount
    ) {
        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.builder()
                        .id(creator.getId())
                        .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                        .email(creator.getEmail() != null ? creator.getEmail() : "")
                        .build() : null)
                .participants(participants.stream()
                        .filter(p -> p != null && p.getId() != null)
                        .map(p -> UserResponse.builder()
                                .id(p.getId())
                                .name(p.getName() != null ? p.getName() : "알 수 없음")
                                .email(p.getEmail() != null ? p.getEmail() : "")
                                .build())
                        .collect(Collectors.toList()))
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(creator != null && creator.getId().equals(name))
                .recentMessageCount(recentMessageCount)
                .build();
    }

    private record RoomOperation(Room room, RoomResponse response) {
    }

    private Map<String, User> findUsersById(Collection<Room> rooms) {
        Set<String> userIds = rooms.stream()
            .flatMap(room -> Stream.concat(
                Stream.of(room.getCreator()),
                participantIds(room)))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));

        if (userIds.isEmpty()) {
            return Map.of();
        }

        return StreamSupport.stream(userRepository.findAllById(userIds).spliterator(), false)
            .filter(user -> user.getId() != null)
            .collect(Collectors.toMap(User::getId, user -> user, (first, ignored) -> first));
    }

    private Stream<String> participantIds(Room room) {
        return room.getParticipantIds() == null
            ? Stream.empty()
            : room.getParticipantIds().stream();
    }
}
