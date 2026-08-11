import { useRef, useEffect, useCallback } from 'react';
import socketClient from '@/lib/socket/socketClient';
import { useAuth } from '@/contexts/AuthContext';
import { Toast } from '@/components/Toast';
import api, { getAuthHeaders } from '@/lib/api/client';
import {
  createRoomEventHandlers,
  processLoadedRoomMessages,
} from './roomEventHandlers';

export const useRoomHandling = ({
  roomId,
  route,
  state,
  refs,
  actions,
  cleanup,
  handleReactionUpdate,
  handleSessionError,
}) => {
  const { onReplace } = route;
  const { currentUser } = state;
  const {
    socketRef,
    attachSocket,
    mountedRef,
    initializingRef,
    setupCompleteRef,
    userRooms,
    processedMessageIds,
    messageProcessingRef,
    initialLoadCompletedRef,
  } = refs;
  const {
    setRoom,
    setError,
    setMessages,
    setHasMoreMessages,
    setLoadingMessages,
    setupStarted,
    setupSucceeded,
    setupFailed,
  } = actions;
  const { user, logout } = useAuth();
  const setupPromiseRef = useRef(null);
  const roomEventsUnsubscribeRef = useRef(null);
  const MAX_SOCKET_RECONNECT_ATTEMPTS = 3;
  const MAX_MESSAGE_RETRY_ATTEMPTS = 3;
  const MESSAGE_TIMEOUT = 5000;
  const MESSAGE_RETRY_DELAY = 2000;

  const processMessages = useCallback(
    (loadedMessages, hasMore, isInitialLoad = false) => {
      processLoadedRoomMessages({
        loadedMessages,
        hasMore,
        isInitialLoad,
        processedMessageIds,
        setMessages,
        setHasMoreMessages,
        initialLoadCompletedRef,
      });
    },
    [
      processedMessageIds,
      setMessages,
      setHasMoreMessages,
      initialLoadCompletedRef,
    ]
  );

  const setupEventListeners = useCallback(() => {
    if (!socketRef.current || !mountedRef.current) return;

    if (roomEventsUnsubscribeRef.current) {
      roomEventsUnsubscribeRef.current();
      roomEventsUnsubscribeRef.current = null;
    }

    const roomEventHandlers = createRoomEventHandlers({
      mountedRef,
      messageProcessingRef,
      processedMessageIds,
      initialLoadCompletedRef,
      processMessages,
      setRoom,
      setMessages,
      setLoadingMessages,
      setError,
      setHasMoreMessages,
      cleanup,
      logout,
      onReplace,
      handleReactionUpdate,
      showRejectedMessage: Toast.error.bind(Toast),
    });
    const unsubscribeSocketEvents = socketClient.subscribeRoomEvents(
      socketRef.current,
      roomEventHandlers
    );

    roomEventsUnsubscribeRef.current = () => {
      unsubscribeSocketEvents();
      roomEventHandlers.dispose();
    };
  }, [
    processMessages,
    setHasMoreMessages,
    cleanup,
    handleReactionUpdate,
    setLoadingMessages,
    setError,
    logout,
    socketRef,
    mountedRef,
    messageProcessingRef,
    processedMessageIds,
    initialLoadCompletedRef,
    setRoom,
    setMessages,
    onReplace,
  ]);

  const setupSocket = useCallback(async () => {
    try {
      if (!user?.token || !user?.sessionId) {
        throw new Error('Invalid authentication state');
      }

      if (socketRef.current?.connected) {
        return socketRef.current;
      }

      if (socketRef.current) {
        const currentSocket = socketRef.current;

        if (userRooms.current?.get(currentSocket.id)) {
          await new Promise((resolve) => {
            socketClient.leaveRoom(
              userRooms.current.get(currentSocket.id),
              currentSocket
            );
            setTimeout(resolve, 1000);
          });
          userRooms.current.delete(currentSocket.id);
        }

        currentSocket.disconnect();
        currentSocket.removeAllListeners();
        attachSocket(null);

        await new Promise((resolve) => setTimeout(resolve, 2000));
      }

      const socket = await socketClient.connect({
        auth: {
          token: user.token,
          sessionId: user.sessionId,
        },
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: MAX_SOCKET_RECONNECT_ATTEMPTS,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 3000,
        timeout: 10000,
        connectionTimeoutMs: 15000,
        pingTimeout: 10000,
        pingInterval: 8000,
        forceNew: true,
        autoConnect: true,
      });

      return socket;
    } catch (error) {
      if (error.message === 'Invalid authentication state') {
        onReplace('/?error=auth_required');
      }
      throw error;
    }
  }, [userRooms, onReplace, socketRef, attachSocket, user]);

  const fetchRoomData = useCallback(
    async (roomId) => {
      if (!user?.token || !user?.sessionId) {
        await handleSessionError();
        throw new Error('인증 정보가 유효하지 않습니다.');
      }

      if (!roomId || !mountedRef.current) {
        throw new Error('채팅방 정보가 올바르지 않습니다.');
      }

      const requestRoom = (session) => api.get(`/api/rooms/${roomId}`, {
        handleAuthError: false,
        headers: getAuthHeaders(session),
      });

      let response;
      try {
        response = await requestRoom(user);
      } catch (error) {
        if (error.response?.status !== 401) {
          throw error;
        }

        const refreshedToken = await handleSessionError();
        if (!refreshedToken || !mountedRef.current) {
          throw new Error('인증이 만료되었습니다.');
        }

        response = await requestRoom({
          ...user,
          token: refreshedToken,
        });
      }

      const data = response.data;
      if (!data.success || !data.data) {
        throw new Error('채팅방 데이터가 올바르지 않습니다.');
      }

      return data.data;
    },
    [user, mountedRef, handleSessionError]
  );

  const joinRoom = useCallback(
    async (roomId) => {
      if (!roomId || !mountedRef.current) {
        throw new Error('잘못된 채팅방 정보입니다.');
      }

      const socket = socketRef.current;
      if (!socket?.connected) {
        throw new Error('Socket not connected');
      }

      const data = await socketClient.joinRoomAndWait(roomId, socket);
      userRooms.current?.set(socket.id, roomId);
      return data;
    },
    [socketRef, mountedRef, userRooms]
  );

  const loadInitialMessages = useCallback(
    async (roomId) => {
      const loadMessagesWithRetry = async (retryCount = 0) => {
        const socket = socketRef.current;
        if (!socket?.connected) {
          throw new Error('Socket not connected');
        }

        try {
          const response = await socketClient.fetchPreviousMessagesAndWait(
            { roomId, limit: 30 },
            socket,
            { timeoutMs: MESSAGE_TIMEOUT }
          );

          if (!response || !Array.isArray(response.messages)) {
            throw new Error('잘못된 메시지 응답 형식입니다.');
          }

          processMessages(response.messages, response.hasMore, true);
          return response;
        } catch (error) {
          if (retryCount < MAX_MESSAGE_RETRY_ATTEMPTS) {
            await new Promise((resolve) =>
              setTimeout(resolve, MESSAGE_RETRY_DELAY)
            );
            return loadMessagesWithRetry(retryCount + 1);
          }

          throw error;
        }
      };

      try {
        return await loadMessagesWithRetry();
      } catch (error) {
        if (!socketRef.current?.connected) {
          // setupSocket 은 낡은 소켓을 버리고 새 소켓을 반환한다. 받아서 걸어주지
          // 않으면 ref 가 비어 있어 재시도가 곧바로 'Socket not connected' 로 죽는다.
          attachSocket(await setupSocket());
          return loadMessagesWithRetry();
        }
        throw error;
      }
    },
    [socketRef, attachSocket, processMessages, setupSocket]
  );

  // 재연결 뒤 필요한 것은 방 참가 상태 복구와 메시지 이력 재조회다.
  // socket.io 가 같은 소켓을 되살렸으므로 방 이벤트 구독은 그대로 살아 있다.
  const rejoinRoom = useCallback(async () => {
    const socket = socketRef.current;
    if (!roomId || !mountedRef.current || !socket?.connected) {
      return;
    }

    await joinRoom(roomId);
    await loadInitialMessages(roomId);

    if (mountedRef.current) {
      setupCompleteRef.current = true;
    }
  }, [
    roomId,
    socketRef,
    mountedRef,
    setupCompleteRef,
    joinRoom,
    loadInitialMessages,
  ]);

  const setupRoom = useCallback(async () => {
    if (setupPromiseRef.current) {
      return setupPromiseRef.current;
    }

    setupPromiseRef.current = (async () => {
      try {
        initializingRef.current = true;
        setupStarted();
        // 1. Socket Setup
        const socket = await setupSocket();
        attachSocket(socket);

        if (!socket?.connected) {
          throw new Error('Socket not connected');
        }

        // 2. Fetch Room Data
        const roomData = await fetchRoomData(roomId);

        // Ensure current user is included in participants for display
        if (currentUser && roomData.participants) {
          const isUserInParticipants = roomData.participants.some(
            (p) => p._id === currentUser.id || p.id === currentUser.id
          );

          if (!isUserInParticipants) {
            roomData.participants = [
              ...roomData.participants,
              {
                _id: currentUser.id,
                id: currentUser.id,
                name: currentUser.name,
                email: currentUser.email,
              },
            ];
          }
        }

        // 3. Setup Event Listeners
        if (mountedRef.current) {
          setupEventListeners();
        }

        // 4. Join Room and Load Messages
        if (!mountedRef.current) {
          return;
        }

        await joinRoom(roomId);
        await loadInitialMessages(roomId);

        if (mountedRef.current) {
          setupCompleteRef.current = true;
          setupSucceeded(roomData);
        }
      } catch (error) {
        if (mountedRef.current) {
          const errorMessage = error.message.includes('시간 초과')
            ? '채팅방 연결 시간이 초과되었습니다.'
            : error.message || '채팅방 연결에 실패했습니다.';

          setupFailed(errorMessage);
          cleanup('ERROR');

          if (socketRef.current) {
            socketRef.current.disconnect();
            attachSocket(null);
          }
        }

        throw error;
      } finally {
        if (mountedRef.current) {
          initializingRef.current = false;
        }

        setupPromiseRef.current = null;
      }
    })();

    return setupPromiseRef.current;
  }, [
    roomId,
    socketRef,
    attachSocket,
    mountedRef,
    setupSocket,
    fetchRoomData,
    joinRoom,
    loadInitialMessages,
    cleanup,
    setupEventListeners,
    setupStarted,
    setupSucceeded,
    setupFailed,
    currentUser,
    initializingRef,
    setupCompleteRef,
  ]);

  useEffect(() => {
    return () => {
      // React Strict Mode는 개발 모드에서 effect를 setup → cleanup → setup
      // 순서로 재실행한다. 진행 중인 Promise를 여기서 비우면 두 번째
      // setup이 같은 방 초기화를 중복 실행한다. Promise의 finally가
      // 자신의 완료 시점에 ref를 정리하도록 둔다.
      initializingRef.current = false;
      setupCompleteRef.current = false;

      if (roomEventsUnsubscribeRef.current) {
        roomEventsUnsubscribeRef.current();
        roomEventsUnsubscribeRef.current = null;
      }

      // 언마운트 경로는 attachSocket 을 쓰지 않는다. 사라지는 컴포넌트에
       // 소켓 교체를 통지할 구독자가 없다.
      if (socketRef.current) {
        socketRef.current.disconnect();
        socketRef.current = null;
      }
    };
  }, []);

  return {
    setupRoom,
    rejoinRoom,
    loadInitialMessages,
  };
};

export default useRoomHandling;
