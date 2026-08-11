import { useCallback, useEffect, useRef } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';

export const useReactionHandling = ({ currentUser, messages, setMessages }) => {
  const messagesRef = useRef(messages);
  const currentUserId = currentUser?.id;

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  const handleReactionAdd = useCallback(async (messageId, reaction) => {
    let previousReactions;
    let optimisticUpdateStarted = false;

    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      previousReactions = messagesRef.current.find(
        message => message._id === messageId
      )?.reactions || {};

      // 낙관적 업데이트
      setMessages(prevMessages =>
        prevMessages.map(msg => {
          if (msg._id === messageId) {
            const currentReactions = msg.reactions || {};
            const currentUsers = currentReactions[reaction] || [];

            // 중복 추가 방지
            if (!currentUsers.includes(currentUserId)) {
              return {
                ...msg,
                reactions: {
                  ...currentReactions,
                  [reaction]: [...currentUsers, currentUserId]
                }
              };
            }
          }
          return msg;
        })
      );
      optimisticUpdateStarted = true;

      await socketClient.sendMessageReaction(messageId, reaction, 'add');

    } catch (error) {
      console.error('Add reaction error:', error);
      Toast.error('리액션 추가에 실패했습니다.');

      // 실패 시 롤백
      if (optimisticUpdateStarted) {
        setMessages(prevMessages =>
          prevMessages.map(msg =>
            msg._id === messageId ?
            { ...msg, reactions: previousReactions } :
            msg
          )
        );
      }
    }
  }, [currentUserId, setMessages]);

  const handleReactionRemove = useCallback(async (messageId, reaction) => {
    let previousReactions;
    let optimisticUpdateStarted = false;

    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      previousReactions = messagesRef.current.find(
        message => message._id === messageId
      )?.reactions || {};

      // 낙관적 업데이트
      setMessages(prevMessages =>
        prevMessages.map(msg => {
          if (msg._id === messageId) {
            const currentReactions = msg.reactions || {};
            const currentUsers = currentReactions[reaction] || [];
            return {
              ...msg,
              reactions: {
                ...currentReactions,
                [reaction]: currentUsers.filter(id => id !== currentUserId)
              }
            };
          }
          return msg;
        })
      );
      optimisticUpdateStarted = true;

      await socketClient.sendMessageReaction(messageId, reaction, 'remove');

    } catch (error) {
      console.error('Remove reaction error:', error);
      Toast.error('리액션 제거에 실패했습니다.');

      // 실패 시 롤백
      if (optimisticUpdateStarted) {
        setMessages(prevMessages =>
          prevMessages.map(msg =>
            msg._id === messageId ?
            { ...msg, reactions: previousReactions } :
            msg
          )
        );
      }
    }
  }, [currentUserId, setMessages]);

  const handleReactionUpdate = useCallback(({ messageId, reactions }) => {
    setMessages(prevMessages =>
      prevMessages.map(msg =>
        msg._id === messageId ? { ...msg, reactions } : msg
      )
    );
  }, [setMessages]);

  return {
    handleReactionAdd,
    handleReactionRemove,
    handleReactionUpdate
  };
};

export default useReactionHandling;
