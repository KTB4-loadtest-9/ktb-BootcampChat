import React, { useMemo, useEffect, useState, useCallback, useRef } from 'react';
import { ConfirmOutlineIcon } from '@vapor-ui/icons';
import { Text, HStack } from '@vapor-ui/core';
import socketClient from '@/lib/socket/socketClient';
import { enqueueReadReceipt } from '@/lib/readStatus/readReceiptBatcher';
import { observeReadVisibility } from '@/lib/readStatus/readVisibilityObserver';

const ReadStatus = ({ 
  messageType = 'text',
  participants = [],
  readers = [],
  className = '',
  messageId = null,
  messageRef = null, // 메시지 요소의 ref 추가
  currentUserId = null // 현재 사용자 ID 추가
}) => {
  const [hasMarkedAsRead, setHasMarkedAsRead] = useState(false);
  const [retryWhenConnected, setRetryWhenConnected] = useState(false);
  const isMountedRef = useRef(true);
  const markingAsReadRef = useRef(false);
  const checkConnectionBeforeRetryRef = useRef(false);
  const statusRef = useRef(null);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  // 읽지 않은 참여자 명단 생성 
  const unreadParticipants = useMemo(() => {
    if (messageType === 'system') return [];
    
    return participants.filter(participant => 
      !readers.some(reader => 
        reader.userId === participant._id || 
        reader.userId === participant.id
      )
    );
  }, [participants, readers, messageType]);

  // 읽지 않은 참여자 수 계산
  const unreadCount = useMemo(() => {
    if (messageType === 'system') {
      return 0;
    }
    return unreadParticipants.length;
  }, [unreadParticipants.length, messageType]);

  const isAlreadyRead = useMemo(() => (
    readers.some(reader => reader.userId === currentUserId)
  ), [readers, currentUserId]);

  // 메시지를 읽음으로 표시하는 함수
  const markMessageAsRead = useCallback(async () => {
    if (!messageId || !currentUserId || markingAsReadRef.current ||
        hasMarkedAsRead || isAlreadyRead ||
        messageType === 'system') {
      return;
    }

    if (!socketClient.canSend()) {
      checkConnectionBeforeRetryRef.current = true;
      setRetryWhenConnected(true);
      return;
    }

    markingAsReadRef.current = true;
    setRetryWhenConnected(false);
    setHasMarkedAsRead(true);
    try {
      // 같은 프레임에 읽힌 메시지 ID를 모아 Socket.IO로 한 번에 전송
      await enqueueReadReceipt(messageId);

    } catch (error) {
      markingAsReadRef.current = false;
      if (isMountedRef.current) {
        setHasMarkedAsRead(false);
        checkConnectionBeforeRetryRef.current =
          !retryWhenConnected || !socketClient.canSend();
        setRetryWhenConnected(true);
      }
      console.error('Error marking message as read:', error);
    }
  }, [
    messageId,
    currentUserId,
    hasMarkedAsRead,
    isAlreadyRead,
    messageType,
    retryWhenConnected,
  ]);

  useEffect(() => {
    if (
      !retryWhenConnected ||
      hasMarkedAsRead ||
      isAlreadyRead ||
      messageType === 'system'
    ) {
      return;
    }

    const retryIfSendable = (connected) => {
      if (!connected || !socketClient.canSend() || !isMountedRef.current) {
        return;
      }

      checkConnectionBeforeRetryRef.current = false;
      setRetryWhenConnected(false);
      markMessageAsRead();
    };

    const unsubscribe = socketClient.subscribeConnectionState(retryIfSendable);

    // 실패와 구독 사이에 연결이 복구된 경우에도 재시도를 놓치지 않는다.
    if (checkConnectionBeforeRetryRef.current && socketClient.canSend()) {
      retryIfSendable(true);
    }

    return unsubscribe;
  }, [
    retryWhenConnected,
    hasMarkedAsRead,
    isAlreadyRead,
    messageType,
    markMessageAsRead,
  ]);

  // Intersection Observer 설정
  useEffect(() => {
    if (
      !messageRef?.current ||
      !currentUserId ||
      hasMarkedAsRead ||
      isAlreadyRead ||
      messageType === 'system'
    ) {
      return;
    }

    return observeReadVisibility(messageRef.current, markMessageAsRead);
  }, [
    messageRef,
    currentUserId,
    hasMarkedAsRead,
    isAlreadyRead,
    messageType,
    markMessageAsRead,
  ]);

  // 시스템 메시지는 읽음 상태 표시 안 함
  if (messageType === 'system') {
    return null;
  }

  // 모두 읽은 경우
  if (unreadCount === 0) {
    return (
      <HStack
        className={className}
        ref={statusRef}
        $css={{ gap: '$050', alignItems: 'center' }}
        role="status"
        aria-label="모든 참여자가 메시지를 읽었습니다"
        data-testid="read-status-all-read"
      >
        <HStack $css={{ alignItems: 'center' }}>
          <ConfirmOutlineIcon size={12} className='text-v-success-100' />
          <ConfirmOutlineIcon size={12} className='-ml-1.5 text-v-success-100' />
        </HStack>
        <Text typography="subtitle2" className="text-v-hint-200">모두 읽음</Text>
      </HStack>
    );
  }

  // 읽지 않은 사람이 있는 경우
  return (
    <HStack
      className={className}
      ref={statusRef}
      $css={{ gap: '$050', alignItems: 'center' }}
      role="status"
      aria-label={`${unreadCount}명이 메시지를 읽지 않았습니다`}
      data-testid="read-status-unread"
    >
      <ConfirmOutlineIcon size={12} className="text-v-hint-200" />
      {unreadCount > 0 && (
        <Text typography="subtitle2" className="text-v-hint-200">
          {unreadCount}명 안 읽음
        </Text>
      )}
    </HStack>
  );
};

export default ReadStatus;
