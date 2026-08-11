import { useRef, useEffect } from 'react';
import socketClient from '@/lib/socket/socketClient';

const CONNECTION_STATUS = {
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

const participantCountOf = (room) =>
  room?.participantsCount ?? room?.participants?.length ?? 0;

const hasSameRoomListValues = (currentRoom, nextRoom) =>
  currentRoom.name === nextRoom.name &&
  currentRoom.hasPassword === nextRoom.hasPassword &&
  participantCountOf(currentRoom) === participantCountOf(nextRoom) &&
  currentRoom.recentMessageCount === nextRoom.recentMessageCount &&
  currentRoom.createdAt === nextRoom.createdAt;

export const useRoomsSocket = ({
  currentUser,
  setConnectionStatus,
  rooms,
  setRooms,
  setMetadata,
}) => {
  const socketRef = useRef(null);
  const roomsRef = useRef(rooms);

  useEffect(() => {
    roomsRef.current = rooms;
  }, [rooms]);

  useEffect(() => {
    if (!currentUser?.token) return;

    let isSubscribed = true;
    let subscribedSocket = null;
    let subscribedHandlers = null;

    const connectSocket = async () => {
      setConnectionStatus(CONNECTION_STATUS.CONNECTING);

      try {
        const socket = await socketClient.connect({
          auth: {
            token: currentUser.token,
            sessionId: currentUser.sessionId,
          },
        });

        if (!isSubscribed || !socket) return;

        socketRef.current = socket;

        const handlers = {
          connect: () => {
            setConnectionStatus(CONNECTION_STATUS.CONNECTED);
          },
          disconnect: () => {
            setConnectionStatus(CONNECTION_STATUS.DISCONNECTED);
          },
          error: () => {
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          },
          roomCreated: (newRoom) => {
            if (!newRoom?._id) return;
            const isNewRoom = !roomsRef.current.some(
              (room) => room._id === newRoom._id
            );

            setRooms((prev) => {
              const existingRoom = prev.find(
                (room) => room._id === newRoom._id
              );
              const mergedRoom = existingRoom
                ? { ...existingRoom, ...newRoom }
                : newRoom;

              return [
                mergedRoom,
                ...prev.filter((room) => room._id !== newRoom._id),
              ];
            });

            if (!isNewRoom) return;

            roomsRef.current = [
              newRoom,
              ...roomsRef.current.filter((room) => room._id !== newRoom._id),
            ];
            setMetadata((metadata) => {
              if (!metadata) return metadata;

              const total = metadata.total + 1;
              const pageSize = metadata.pageSize || 1;
              return {
                ...metadata,
                total,
                totalPages: Math.ceil(total / pageSize),
                hasMore: total > (metadata.page + 1) * pageSize,
              };
            });
          },
          roomUpdated: (updatedRoom) => {
            if (!updatedRoom?._id) return;

            setRooms((prev) => {
              const roomIndex = prev.findIndex(
                (room) => room._id === updatedRoom._id
              );
              if (roomIndex === -1) return prev;

              const nextRoom = { ...prev[roomIndex], ...updatedRoom };
              if (hasSameRoomListValues(prev[roomIndex], nextRoom)) return prev;

              const nextRooms = [...prev];
              nextRooms[roomIndex] = nextRoom;
              return nextRooms;
            });
          },
          // 활성도 지표만 담긴 경량 payload이므로 방 정보를 덮지 않고 병합한다
          roomActivity: (activity) => {
            if (!activity?._id) return;
            if (typeof activity.recentMessageCount !== 'number') return;

            setRooms((prev) => {
              const roomIndex = prev.findIndex(
                (room) => room._id === activity._id
              );
              if (roomIndex === -1) return prev;

              const currentRoom = prev[roomIndex];
              if (
                currentRoom.recentMessageCount === activity.recentMessageCount
              ) {
                return prev;
              }

              const nextRooms = [...prev];
              nextRooms[roomIndex] = {
                ...currentRoom,
                recentMessageCount: activity.recentMessageCount,
              };
              return nextRooms;
            });
          },
        };

        Object.entries(handlers).forEach(([event, handler]) => {
          socket.on(event, handler);
        });
        subscribedSocket = socket;
        subscribedHandlers = handlers;

        // connect()는 연결 완료 후 resolve되므로 최초 connect 이벤트는 이미 지나갔다.
        if (socket.connected) {
          setConnectionStatus(CONNECTION_STATUS.CONNECTED);
        }
      } catch (error) {
        if (!isSubscribed) return;

        if (
          error.message?.includes('Authentication required') ||
          error.message?.includes('Invalid session')
        ) {
          // Auth error will be handled by the useAuth context
        }

        setConnectionStatus(CONNECTION_STATUS.ERROR);
      }
    };

    connectSocket();

    return () => {
      isSubscribed = false;

      if (subscribedSocket && subscribedHandlers) {
        Object.entries(subscribedHandlers).forEach(([event, handler]) => {
          subscribedSocket.off?.(event, handler);
        });
      }

      // socketClient 는 인증 세션당 하나의 연결을 공유한다. 목록에서 방으로
      // 이동할 때 여기서 연결까지 끊으면 새 방 화면의 setupSocket 과 경합한다.
      // 이 훅은 자신이 등록한 목록 이벤트만 정리하고, 연결 종료는 로그아웃이나
      // 채팅방 소켓 소유자가 담당한다.
      socketRef.current = null;
    };
  }, [currentUser]); // eslint-disable-line react-hooks/exhaustive-deps

  return { socketRef };
};

export default useRoomsSocket;
