import {
  collectUniqueMessages,
  mergeSortedMessages,
} from '../messages/useMessageList';

export const MESSAGE_BATCH_DELAY_MS = 16;

export const processLoadedRoomMessages = ({
  loadedMessages,
  hasMore,
  isInitialLoad = false,
  processedMessageIds,
  setMessages,
  setHasMoreMessages,
  initialLoadCompletedRef,
}) => {
  if (!Array.isArray(loadedMessages)) {
    throw new Error('Invalid messages format');
  }

  const {
    messages: uniqueLoadedMessages,
    processedMessageIds: nextProcessedMessageIds,
  } = collectUniqueMessages(loadedMessages, processedMessageIds.current);
  processedMessageIds.current = nextProcessedMessageIds;

  let nextMessages;
  setMessages(prev => {
    nextMessages = mergeSortedMessages(prev, uniqueLoadedMessages);
    return nextMessages;
  });
  setHasMoreMessages(hasMore);

  if (isInitialLoad) {
    initialLoadCompletedRef.current = true;
  }

  return nextMessages;
};

export const applyReadReceipts = (messages, { userId, messageIds, timestamp }) =>
  messages.map(msg => {
    if (!messageIds.includes(msg._id)) {
      return msg;
    }

    const alreadyRead = msg.readers?.some(reader =>
      reader.userId === userId || reader._id === userId
    );
    if (alreadyRead) {
      return msg;
    }

    return {
      ...msg,
      readers: [...(msg.readers || []), { userId, readAt: timestamp || new Date() }],
    };
  });

export const createRoomEventHandlers = ({
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
  showRejectedMessage,
  scheduleMessageFlush = callback => setTimeout(callback, MESSAGE_BATCH_DELAY_MS),
  cancelMessageFlush = handle => clearTimeout(handle),
}) => {
  let pendingMessages = [];
  let messageFlushHandle = null;

  const flushPendingMessages = () => {
    if (messageFlushHandle !== null) {
      cancelMessageFlush(messageFlushHandle);
      messageFlushHandle = null;
    }

    if (!mountedRef.current) {
      pendingMessages = [];
      return;
    }

    const messagesToAppend = pendingMessages;
    pendingMessages = [];
    if (messagesToAppend.length > 0) {
      setMessages(previousMessages => (
        mergeSortedMessages(previousMessages, messagesToAppend)
      ));
    }
  };

  const schedulePendingMessageFlush = () => {
    if (messageFlushHandle !== null) return;
    messageFlushHandle = scheduleMessageFlush(flushPendingMessages);
  };

  const dispose = () => {
    if (messageFlushHandle !== null) {
      cancelMessageFlush(messageFlushHandle);
      messageFlushHandle = null;
    }
    pendingMessages = [];
  };

  const handlePreviousMessages = (response) => {
    if (!mountedRef.current || messageProcessingRef.current) return;
    try {
      messageProcessingRef.current = true;
      if (!response || typeof response !== 'object') {
        throw new Error('Invalid response format');
      }
      const { messages: loadedMessages = [], hasMore } = response;
      const isInitialLoad = !initialLoadCompletedRef.current;
      flushPendingMessages();
      processMessages(loadedMessages, hasMore, isInitialLoad);
      setLoadingMessages(false);
    } catch (error) {
      setLoadingMessages(false);
      setError('메시지 처리 중 오류가 발생했습니다.');
      setHasMoreMessages(false);
    } finally {
      messageProcessingRef.current = false;
    }
  };

  return {
    dispose,
    flushPendingMessages,
    onParticipantsUpdate: (participants) => {
      if (!mountedRef.current) return;
      setRoom(prev => ({ ...prev, participants: participants || [] }));
    },
    onMessagesRead: (payload) => {
      if (!mountedRef.current) return;
      pendingMessages = applyReadReceipts(pendingMessages, payload);
      setMessages(prev => applyReadReceipts(prev, payload));
    },
    onMessage: (incoming) => {
      if (!mountedRef.current || messageProcessingRef.current) return;
      if (!incoming?._id || processedMessageIds.current.has(incoming._id)) return;
      processedMessageIds.current.add(incoming._id);
      pendingMessages.push(incoming);
      schedulePendingMessageFlush();
    },
    onPreviousMessagesLoaded: handlePreviousMessages,
    onMessageReactionUpdate: (data) => {
      if (!mountedRef.current) return;
      handleReactionUpdate(data);
    },
    onSessionEnded: () => {
      if (!mountedRef.current) return;
      dispose();
      cleanup();
      logout();
      onReplace('/?error=session_expired');
    },
    onError: (error) => {
      if (!mountedRef.current) return;
      if (error?.code === 'MESSAGE_REJECTED') {
        showRejectedMessage(error.message || '금칙어가 포함되어 메시지를 전송할 수 없습니다.');
        return;
      }
      console.error('Socket error:', error);
      setError(error.message || '채팅 연결에 문제가 발생했습니다.');
    },
  };
};
