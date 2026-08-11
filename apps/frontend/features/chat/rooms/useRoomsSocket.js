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
  setRooms,
  onRoomsChanged,
}) => {
  const socketRef = useRef(null);

  useEffect(() => {
    if (!currentUser?.token) return;

    let isSubscribed = true;

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
            if (typeof onRoomsChanged === 'function') {
              onRoomsChanged();
              return;
            }

            setRooms((prev) => [newRoom, ...prev]);
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
      if (socketRef.current) {
        socketRef.current.disconnect();
        socketRef.current = null;
      }
    };
  }, [currentUser]); // eslint-disable-line react-hooks/exhaustive-deps

  return { socketRef };
};

export default useRoomsSocket;
