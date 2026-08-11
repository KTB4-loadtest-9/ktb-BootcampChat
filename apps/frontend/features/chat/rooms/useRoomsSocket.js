import { useRef, useEffect } from 'react';
import socketClient from '@/lib/socket/socketClient';

const CONNECTION_STATUS = {
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

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

    const connectSocket = async () => {
      try {
        const socket = await socketClient
          .connect({
            auth: {
              token: currentUser.token,
              sessionId: currentUser.sessionId,
            },
          })
          .catch((err) => {
            console.log('Socket connection error:', err);
            setConnectionStatus(CONNECTION_STATUS.ERROR);
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
            const isNewRoom = !roomsRef.current.some((room) => room._id === newRoom._id);

            setRooms((prev) => {
              const existingRoom = prev.find((room) => room._id === newRoom._id);
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
            setRooms((prev) =>
              prev.map((room) =>
                room._id === updatedRoom._id
                  ? { ...room, ...updatedRoom }
                  : room
              )
            );
          },
          // 활성도 지표만 담긴 경량 payload이므로 방 정보를 덮지 않고 병합한다
          roomActivity: (activity) => {
            if (!activity?._id) return;

            setRooms((prev) =>
              prev.map((room) =>
                room._id === activity._id
                  ? { ...room, recentMessageCount: activity.recentMessageCount }
                  : room
              )
            );
          },
        };

        Object.entries(handlers).forEach(([event, handler]) => {
          socket.on(event, handler);
        });
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
