import { useRef, useEffect, useCallback } from 'react';

/**
 * 채팅 메시지 자동 스크롤 훅
 *
 * - 내가 쓴 메시지는 최하단으로 이동한다.
 * - 다른 사용자의 메시지는 하단 근처에 있을 때만 이동한다.
 * - 과거 메시지를 앞에 붙일 때는 현재 위치를 유지한다.
 */
export const useAutoScroll = (
  messages = [],
  currentUserId = null,
  isLoadingMessages = false,
  threshold = 100
) => {
  const containerRef = useRef(null);
  const isNearBottomRef = useRef(true);
  const previousMessagesLengthRef = useRef(0);
  const previousLastMessageIdRef = useRef(null);
  const isAutoScrollingRef = useRef(false);
  const scrollFrameRef = useRef(null);
  const scrollResetTimerRef = useRef(null);
  const previousScrollHeightRef = useRef(0);
  const previousScrollTopRef = useRef(0);
  const isRestoringRef = useRef(false);

  const checkIsNearBottom = useCallback(() => {
    const container = containerRef.current;
    if (!container) return true;

    const { scrollHeight, scrollTop, clientHeight } = container;
    return scrollHeight - (scrollTop + clientHeight) <= threshold;
  }, [threshold]);

  const scrollToBottom = useCallback((behavior = 'smooth') => {
    if (scrollFrameRef.current !== null) {
      cancelAnimationFrame(scrollFrameRef.current);
    }

    scrollFrameRef.current = requestAnimationFrame(() => {
      scrollFrameRef.current = null;
      const container = containerRef.current;
      if (!container) return;

      isAutoScrollingRef.current = true;
      container.scrollTo({ top: container.scrollHeight, behavior });

      if (scrollResetTimerRef.current !== null) {
        clearTimeout(scrollResetTimerRef.current);
      }
      scrollResetTimerRef.current = setTimeout(() => {
        scrollResetTimerRef.current = null;
        isAutoScrollingRef.current = false;
        isNearBottomRef.current = true;
      }, behavior === 'smooth' ? 300 : 0);
    });
  }, []);

  useEffect(() => () => {
    if (scrollFrameRef.current !== null) {
      cancelAnimationFrame(scrollFrameRef.current);
    }
    if (scrollResetTimerRef.current !== null) {
      clearTimeout(scrollResetTimerRef.current);
    }
  }, []);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const handleScroll = () => {
      if (!isAutoScrollingRef.current) {
        isNearBottomRef.current = checkIsNearBottom();
      }
    };

    container.addEventListener('scroll', handleScroll, { passive: true });
    return () => container.removeEventListener('scroll', handleScroll);
  }, [checkIsNearBottom]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    if (isLoadingMessages && !isRestoringRef.current) {
      previousScrollHeightRef.current = container.scrollHeight;
      previousScrollTopRef.current = container.scrollTop;
      isRestoringRef.current = true;
    }
  }, [isLoadingMessages]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !isRestoringRef.current || isLoadingMessages) return;

    const heightDifference = container.scrollHeight - previousScrollHeightRef.current;
    if (heightDifference > 0) {
      container.scrollTop = previousScrollTopRef.current + heightDifference;
    }
    isRestoringRef.current = false;
  }, [messages, isLoadingMessages]);

  useEffect(() => {
    if (isRestoringRef.current || isLoadingMessages) return;

    if (messages.length === 0) {
      previousMessagesLengthRef.current = 0;
      previousLastMessageIdRef.current = null;
      return;
    }
    if (messages.length === previousMessagesLengthRef.current) return;

    if (messages.length < previousMessagesLengthRef.current) {
      previousMessagesLengthRef.current = messages.length;
      const latestMessage = messages[messages.length - 1];
      previousLastMessageIdRef.current = latestMessage?._id || latestMessage?.id || null;
      return;
    }

    const latestMessage = messages[messages.length - 1];
    const latestMessageId = latestMessage?._id || latestMessage?.id || null;
    const previousLength = previousMessagesLengthRef.current;

    if (
      previousLength > 0 &&
      previousLastMessageIdRef.current &&
      previousLastMessageIdRef.current === latestMessageId
    ) {
      previousMessagesLengthRef.current = messages.length;
      return;
    }

    const newMessages = messages.slice(previousLength);
    previousMessagesLengthRef.current = messages.length;
    previousLastMessageIdRef.current = latestMessageId;
    if (newMessages.length === 0 || !latestMessage) return;

    const senderId = latestMessage.sender?._id || latestMessage.sender?.id || latestMessage.sender;
    const isMyMessage = senderId === currentUserId;
    if (isMyMessage || isNearBottomRef.current) {
      scrollToBottom(newMessages.length > 1 ? 'auto' : 'smooth');
    }
  }, [messages, currentUserId, scrollToBottom, isLoadingMessages]);

  useEffect(() => {
    if (messages.length > 0 && previousMessagesLengthRef.current === 0) {
      scrollToBottom('auto');
    }
  }, [messages.length, scrollToBottom]);

  return {
    containerRef,
    scrollToBottom,
    isNearBottom: () => isNearBottomRef.current,
  };
};

export default useAutoScroll;
