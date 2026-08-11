import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';

const ROOMS_PAGE_SIZE = 10;

const createPagination = (metadata = {}, requestedPage = 0, currentCount = 0) => {
  const total = Number.isInteger(metadata.total) && metadata.total >= 0
    ? metadata.total
    : currentCount;
  const pageSize = Number.isInteger(metadata.pageSize) && metadata.pageSize > 0
    ? metadata.pageSize
    : ROOMS_PAGE_SIZE;
  const totalPages = Number.isInteger(metadata.totalPages) && metadata.totalPages > 0
    ? metadata.totalPages
    : Math.max(Math.ceil(total / pageSize), 1);
  const serverPage = Number.isInteger(metadata.page) && metadata.page >= 0
    ? metadata.page
    : requestedPage;
  const page = Math.min(serverPage, totalPages - 1);

  return {
    total,
    page,
    pageSize,
    totalPages,
    hasMore: typeof metadata.hasMore === 'boolean'
      ? metadata.hasMore
      : page < totalPages - 1,
    currentCount: Number.isInteger(metadata.currentCount) && metadata.currentCount >= 0
      ? metadata.currentCount
      : currentCount,
    sort: metadata.sort ?? null,
  };
};

export const useRoomList = ({
  currentUser,
  router,
  connectionStatus,
  setConnectionStatus,
  isRetrying,
  canJoinRooms = connectionStatus === CONNECTION_STATUS.CONNECTED,
}) => {
  const [rooms, setRooms] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [isInitialLoad, setIsInitialLoad] = useState(true);
  const [joiningRoomId, setJoiningRoomId] = useState(null);
  const [joinError, setJoinError] = useState(null);
  const [pagination, setPagination] = useState(() => createPagination());

  const isLoadingRef = useRef(false);
  const joiningRoomRef = useRef(null);

  const handleFetchError = useCallback((error) => {
    let errorMessage = '채팅방 목록을 불러오는데 실패했습니다.';
    let errorType = 'danger';
    let showRetry = !isRetrying;

    if (error.code === 'AUTH_EXPIRED' || error.message === 'AUTH_EXPIRED') {
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

    if (error.isNetworkError || error.message === 'SERVER_UNREACHABLE') {
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

  const loadRooms = useCallback(async (page = 0) => {
    const response = await axiosInstance.get('/api/rooms', {
      params: {
        page,
        size: ROOMS_PAGE_SIZE,
      },
    });
    const payload = response?.data;

    if (payload?.success === false || !Array.isArray(payload?.data)) {
      throw new Error('INVALID_RESPONSE');
    }

    setRooms(payload.data);
    setPagination(createPagination(payload.metadata, page, payload.data.length));
    setConnectionStatus(CONNECTION_STATUS.CONNECTED);
  }, [setConnectionStatus]);

  const fetchRooms = useCallback(async (page = pagination.page) => {
    if (!currentUser?.token || isLoadingRef.current) {
      return false;
    }

    try {
      isLoadingRef.current = true;

      if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
        setConnectionStatus(CONNECTION_STATUS.CONNECTING);
      }
      setLoading(true);
      setError(null);

      await loadRooms(page);

      if (isInitialLoad) {
        setIsInitialLoad(false);
      }

      return true;
    } catch (error) {
      handleFetchError(error);
      return false;
    } finally {
      setLoading(false);
      isLoadingRef.current = false;
    }
  }, [
    currentUser,
    pagination.page,
    connectionStatus,
    setConnectionStatus,
    isInitialLoad,
    loadRooms,
    handleFetchError,
  ]);

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
      if (!silent) {
        setRefreshing(true);
      }

      await loadRooms(pagination.page);
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
      if (!silent) {
        setRefreshing(false);
      }
      isLoadingRef.current = false;
    }
  }, [currentUser, loadRooms, pagination.page]);

  const goToPage = useCallback(async (page) => {
    if (
      !Number.isInteger(page) ||
      page < 0 ||
      page >= pagination.totalPages ||
      page === pagination.page
    ) {
      return false;
    }

    return fetchRooms(page);
  }, [fetchRooms, pagination.page, pagination.totalPages]);

  const clearJoinError = useCallback((roomId) => {
    setJoinError((current) => (
      !roomId || current?.roomId === roomId ? null : current
    ));
  }, []);

  const handleJoinRoom = useCallback(async (roomId, password) => {
    if (!canJoinRooms) {
      setJoinError({
        roomId,
        message: '서버와 실시간 연결이 완료된 후 다시 시도해주세요.',
      });
      return false;
    }

    if (joiningRoomRef.current) {
      return false;
    }

    joiningRoomRef.current = roomId;
    setJoiningRoomId(roomId);
    setJoinError(null);

    try {
      const response = await axiosInstance.post(
        `/api/rooms/${roomId}/join`,
        password ? { password } : {},
        // 방 비밀번호 오류도 401이므로 공통 인증 만료 처리와 구분한다.
        { handleAuthError: false }
      );

      if (response.data.success) {
        router.push(`/chat/${roomId}`);
        return true;
      }

      setJoinError({
        roomId,
        message: response.data?.message || '입장에 실패했습니다.',
      });
      return false;
    } catch (error) {
      const status = error.response?.status ?? error.status;
      const responseMessage = error.response?.data?.message ?? error.data?.message;
      let errorMessage = '입장에 실패했습니다.';

      if (status === 401 && responseMessage?.includes('비밀번호')) {
        errorMessage = responseMessage;
      } else if (status === 401) {
        errorMessage = '인증이 만료되었습니다. 다시 로그인해주세요.';
        setError({
          title: '인증 만료',
          message: errorMessage,
          type: 'danger',
          showRetry: false,
        });
        setConnectionStatus(CONNECTION_STATUS.ERROR);
      } else if (status === 404) {
        errorMessage = '채팅방을 찾을 수 없습니다.';
      } else if (status === 403) {
        errorMessage = '채팅방 입장 권한이 없습니다.';
      }

      setJoinError({
        roomId,
        message: responseMessage || errorMessage,
      });
      return false;
    } finally {
      if (joiningRoomRef.current === roomId) {
        joiningRoomRef.current = null;
        setJoiningRoomId(null);
      }
    }
  }, [canJoinRooms, router, setConnectionStatus]);

  return {
    rooms,
    setRooms,
    error,
    setError,
    loading,
    refreshing,
    joiningRoomId,
    joinError,
    clearJoinError,
    pagination,
    fetchRooms,
    refreshRooms,
    goToPage,
    handleJoinRoom,
  };
};

export default useRoomList;
