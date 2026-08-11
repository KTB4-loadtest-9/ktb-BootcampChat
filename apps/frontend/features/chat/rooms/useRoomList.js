import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';

export const ROOM_PAGE_SIZE = 20;

export const mergeRoomPage = (currentRooms, nextRooms) => {
  const mergedRooms = [...currentRooms];
  const indexes = new Map(
    mergedRooms
      .map((room, index) => [room?._id, index])
      .filter(([roomId]) => roomId)
  );

  nextRooms.forEach((room) => {
    const roomId = room?._id;
    if (!roomId) {
      mergedRooms.push(room);
      return;
    }

    const existingIndex = indexes.get(roomId);
    if (existingIndex === undefined) {
      indexes.set(roomId, mergedRooms.length);
      mergedRooms.push(room);
    }
  });

  return mergedRooms;
};

export const useRoomList = ({
  currentUser,
  router,
  connectionStatus,
  setConnectionStatus,
  isRetrying,
  attemptConnection,
}) => {
  const [rooms, setRooms] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [isInitialLoad, setIsInitialLoad] = useState(true);
  const [joiningRoom, setJoiningRoom] = useState(false);
  const [metadata, setMetadata] = useState(null);

  const isLoadingRef = useRef(false);
  const metadataRef = useRef(null);

  const updateMetadata = useCallback((updater) => {
    const currentMetadata = metadataRef.current;
    const nextMetadata =
      typeof updater === 'function' ? updater(currentMetadata) : updater;
    metadataRef.current = nextMetadata;
    setMetadata(nextMetadata);
  }, []);

  const handleFetchError = useCallback((error) => {
    let errorMessage = '채팅방 목록을 불러오는데 실패했습니다.';
    let errorType = 'danger';
    let showRetry = !isRetrying;

    if (error.message === 'AUTH_EXPIRED') {
      errorMessage = '인증이 만료되었습니다. 다시 로그인해주세요.';
      errorType = 'danger';
      showRetry = false;

      setError({
        title: '인증 만료',
        message: errorMessage,
        type: errorType,
        showRetry,
      });

      setConnectionStatus(CONNECTION_STATUS.ERROR);
      return;
    }

    if (error.message === 'SERVER_UNREACHABLE') {
      errorMessage = '서버와 연결할 수 없습니다. 다시 시도해주세요.';
      errorType = 'warning';
      showRetry = true;
    }

    setError({
      title: '채팅방 목록 로드 실패',
      message: errorMessage,
      type: errorType,
      showRetry,
    });

    setConnectionStatus(CONNECTION_STATUS.ERROR);
  }, [isRetrying, setConnectionStatus]);

  const loadRooms = useCallback(async ({ page = 0, append = false } = {}) => {
    await attemptConnection();

    const response = await axiosInstance.get('/api/rooms', {
      params: { page, pageSize: ROOM_PAGE_SIZE },
    });

    if (!response?.data?.data) {
      throw new Error('INVALID_RESPONSE');
    }

    const nextRooms = response.data.data;
    const nextMetadata = response.data.metadata ?? null;

    setRooms((currentRooms) =>
      append ? mergeRoomPage(currentRooms, nextRooms) : nextRooms
    );
    updateMetadata(nextMetadata);
  }, [attemptConnection, updateMetadata]);

  const fetchRooms = useCallback(async () => {
    if (!currentUser?.token || isLoadingRef.current) {
      return;
    }

    try {
      isLoadingRef.current = true;

      setLoading(true);
      setError(null);

      await loadRooms({ page: 0 });

      if (isInitialLoad) {
        setIsInitialLoad(false);
      }
    } catch (error) {
      handleFetchError(error);
    } finally {
      setLoading(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, isInitialLoad, loadRooms, handleFetchError]);

  /**
   * 이미 그려진 목록을 유지한 채 다시 조회한다.
   * 자동 갱신(silent)은 실패해도 화면을 흔들지 않고 다음 주기를 기다린다.
   */
  const refreshRooms = useCallback(async ({ silent = false } = {}) => {
    if (!currentUser?.token || isLoadingRef.current) {
      return false;
    }

    try {
      isLoadingRef.current = true;
      setRefreshing(true);

      await loadRooms({ page: 0 });
      setError(null);

      return true;
    } catch (error) {
      if (!silent) {
        setError({
          title: '채팅방 목록 갱신 실패',
          message: '목록을 갱신하지 못했습니다. 잠시 후 다시 시도해주세요.',
          type: 'warning',
          showRetry: false,
        });
      }

      return false;
    } finally {
      setRefreshing(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, loadRooms]);

  const loadMoreRooms = useCallback(async () => {
    const currentMetadata = metadataRef.current;
    if (
      !currentUser?.token ||
      !currentMetadata?.hasMore ||
      isLoadingRef.current
    ) {
      return false;
    }

    try {
      isLoadingRef.current = true;
      setLoadingMore(true);
      await loadRooms({ page: currentMetadata.page + 1, append: true });
      setError(null);
      return true;
    } catch (error) {
      handleFetchError(error);
      return false;
    } finally {
      setLoadingMore(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, handleFetchError, loadRooms]);

  const handleJoinRoom = useCallback(async (roomId) => {
    if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
      setError({
        title: '채팅방 입장 실패',
        message: '서버와 연결이 끊어져 있습니다.',
        type: 'danger',
      });
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await axiosInstance.post(`/api/rooms/${roomId}/join`, {});

      if (response.data.success) {
        router.push(`/chat/${roomId}`);
      }
    } catch (error) {
      let errorMessage = '입장에 실패했습니다.';
      if (error.response?.status === 404) {
        errorMessage = '채팅방을 찾을 수 없습니다.';
      } else if (error.response?.status === 403) {
        errorMessage = '채팅방 입장 권한이 없습니다.';
      }

      setError({
        title: '채팅방 입장 실패',
        message: error.response?.data?.message || errorMessage,
        type: 'danger',
      });
    } finally {
      setJoiningRoom(false);
    }
  }, [connectionStatus, router]);

  return {
    rooms,
    setRooms,
    error,
    setError,
    setMetadata: updateMetadata,
    loading,
    loadingMore,
    refreshing,
    metadata,
    joiningRoom,
    fetchRooms,
    loadMoreRooms,
    refreshRooms,
    handleJoinRoom,
  };
};

export default useRoomList;
