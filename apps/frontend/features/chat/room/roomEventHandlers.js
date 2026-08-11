import { deriveUniqueSortedMessages } from '../messages/useMessageList';

export const createMessageIndex = messages => new Map(
  messages.map((message, index) => [message._id, index])
);

const replaceMessageIndex = (messageIndexById, messages) => {
  messageIndexById.clear();
  for (let index = 0; index < messages.length; index += 1) {
    const messageId = messages[index]?._id;
    if (messageId) {
      messageIndexById.set(messageId, index);
    }
  }
};

export const processLoadedRoomMessages = ({
  loadedMessages,
  hasMore,
  isInitialLoad = false,
  processedMessageIds,
  messageIndexById,
  setMessages,
  setHasMoreMessages,
  initialLoadCompletedRef,
}) => {
  if (!Array.isArray(loadedMessages)) {
    throw new Error('Invalid messages format');
  }

  const processedSnapshot = new Set(processedMessageIds.current);
  processedMessageIds.current = deriveUniqueSortedMessages(
    [],
    loadedMessages,
    processedSnapshot
  ).processedMessageIds;

  let nextMessages;
  setMessages(prev => {
    nextMessages = deriveUniqueSortedMessages(prev, loadedMessages, processedSnapshot).messages;
    if (messageIndexById?.current) {
      replaceMessageIndex(messageIndexById.current, nextMessages);
    }
    return nextMessages;
  });
  setHasMoreMessages(hasMore);

  if (isInitialLoad) {
    initialLoadCompletedRef.current = true;
  }

  return nextMessages;
};

export const applyReadReceipts = (
  messages,
  { userId, messageIds, timestamp },
  messageIndexById,
) => {
  if (!userId || !Array.isArray(messageIds) || messageIds.length === 0) {
    return messages;
  }

  const activeIndex = messageIndexById || createMessageIndex(messages);
  const hasStaleIndex = activeIndex.size !== messages.length ||
    messageIds.some(messageId => {
      const index = activeIndex.get(messageId);
      return index !== undefined && messages[index]?._id !== messageId;
    });

  if (hasStaleIndex) {
    replaceMessageIndex(activeIndex, messages);
  }

  let nextMessages = messages;
  const readAt = timestamp || new Date();

  for (const messageId of new Set(messageIds)) {
    const index = activeIndex.get(messageId);
    if (index === undefined) continue;

    const message = messages[index];
    if (!message) continue;

    const alreadyRead = message.readers?.some(reader =>
      reader.userId === userId || reader._id === userId
    );
    if (alreadyRead) continue;

    if (nextMessages === messages) {
      nextMessages = messages.slice();
    }
    nextMessages[index] = {
      ...message,
      readers: [...(message.readers || []), { userId, readAt }],
    };
  }

  return nextMessages;
};

export const appendIncomingMessage = (messages, incoming) => {
  if (!incoming?._id) {
    return messages;
  }

  if (messages.some(msg => msg._id === incoming._id)) {
    return messages;
  }

  return [...messages, incoming];
};

export const createRoomEventHandlers = ({
  mountedRef,
  messageProcessingRef,
  processedMessageIds,
  messageIndexById,
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
}) => {
  const handlePreviousMessages = (response) => {
    if (!mountedRef.current || messageProcessingRef.current) return;
    try {
      messageProcessingRef.current = true;
      if (!response || typeof response !== 'object') {
        throw new Error('Invalid response format');
      }
      const { messages: loadedMessages = [], hasMore } = response;
      const isInitialLoad = !initialLoadCompletedRef.current;
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
    onParticipantsUpdate: (participants) => {
      if (!mountedRef.current) return;
      setRoom(prev => ({ ...prev, participants: participants || [] }));
    },
    onMessagesRead: (payload) => {
      if (!mountedRef.current) return;
      setMessages(prev => applyReadReceipts(
        prev,
        payload,
        messageIndexById?.current,
      ));
    },
    onMessage: (incoming) => {
      if (!mountedRef.current || messageProcessingRef.current) return;
      if (!incoming?._id || processedMessageIds.current.has(incoming._id)) return;
      processedMessageIds.current.add(incoming._id);
      setMessages(prev => {
        const nextMessages = appendIncomingMessage(prev, incoming);
        if (nextMessages !== prev && messageIndexById?.current) {
          messageIndexById.current.set(incoming._id, nextMessages.length - 1);
        }
        return nextMessages;
      });
    },
    onPreviousMessagesLoaded: handlePreviousMessages,
    onMessageReactionUpdate: (data) => {
      if (!mountedRef.current) return;
      handleReactionUpdate(data);
    },
    onSessionEnded: () => {
      if (!mountedRef.current) return;
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
