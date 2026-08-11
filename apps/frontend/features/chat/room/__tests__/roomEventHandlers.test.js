import { describe, expect, it, vi } from 'vitest';
import {
  applyReadReceipts,
  createRoomEventHandlers,
  processLoadedRoomMessages,
} from '../roomEventHandlers';

describe('roomEventHandlers', () => {
  it('processes loaded messages through the shared message list reducer', () => {
    const processedMessageIds = { current: new Set(['message-1']) };
    const initialLoadCompletedRef = { current: false };
    const setMessages = vi.fn((updater) => {
      const currentMessages = [
        { _id: 'message-1', timestamp: '2026-07-07T00:00:02.000Z' },
      ];
      return updater(currentMessages);
    });
    const setHasMoreMessages = vi.fn();

    const result = processLoadedRoomMessages({
      loadedMessages: [
        { _id: 'message-1', timestamp: '2026-07-07T00:00:02.000Z' },
        { _id: 'message-2', timestamp: '2026-07-07T00:00:01.000Z' },
      ],
      hasMore: false,
      isInitialLoad: true,
      processedMessageIds,
      setMessages,
      setHasMoreMessages,
      initialLoadCompletedRef,
    });

    expect(result.map((message) => message._id)).toEqual([
      'message-2',
      'message-1',
    ]);
    expect(processedMessageIds.current.has('message-2')).toBe(true);
    expect(setHasMoreMessages).toHaveBeenCalledWith(false);
    expect(initialLoadCompletedRef.current).toBe(true);
  });

  it('applies read receipts without duplicating existing readers', () => {
    const messages = [
      {
        _id: 'message-1',
        readers: [{ userId: 'user-2', readAt: 'existing' }],
      },
      {
        _id: 'message-2',
        readers: [],
      },
    ];

    expect(
      applyReadReceipts(messages, {
        userId: 'user-2',
        messageIds: ['message-1', 'message-2'],
        timestamp: '2026-07-07T00:00:00.000Z',
      })
    ).toEqual([
      {
        _id: 'message-1',
        readers: [{ userId: 'user-2', readAt: 'existing' }],
      },
      {
        _id: 'message-2',
        readers: [{ userId: 'user-2', readAt: '2026-07-07T00:00:00.000Z' }],
      },
    ]);
  });

  it('keeps live messages when the updater is invoked twice (StrictMode)', () => {
    const mountedRef = { current: true };
    const processedMessageIds = { current: new Set() };
    let committed = [];
    const setMessages = vi.fn((updater) => {
      // React StrictMode invokes state updaters twice with the same base state
      // in development to surface impure updaters. Both calls must agree.
      const first = updater(committed);
      const second = updater(committed);
      expect(second).toEqual(first);
      committed = second;
    });

    const handlers = createRoomEventHandlers({
      mountedRef,
      messageProcessingRef: { current: false },
      processedMessageIds,
      initialLoadCompletedRef: { current: true },
      processMessages: vi.fn(),
      setRoom: vi.fn(),
      setMessages,
      setLoadingMessages: vi.fn(),
      setError: vi.fn(),
      setHasMoreMessages: vi.fn(),
      cleanup: vi.fn(),
      logout: vi.fn(),
      onReplace: vi.fn(),
      handleReactionUpdate: vi.fn(),
      showRejectedMessage: vi.fn(),
      scheduleMessageFlush: (callback) => {
        callback();
        return null;
      },
    });

    handlers.onMessage({ _id: 'message-live' });

    expect(committed.map((message) => message._id)).toEqual(['message-live']);
  });

  it('deduplicates the same message when history and live events arrive in either order', () => {
    const createMessageFlow = () => {
      const processedMessageIds = { current: new Set() };
      const initialLoadCompletedRef = { current: false };
      const messageProcessingRef = { current: false };
      let messages = [];
      const setMessages = vi.fn((updater) => {
        messages = updater(messages);
      });
      const setHasMoreMessages = vi.fn();

      const handlers = createRoomEventHandlers({
        mountedRef: { current: true },
        messageProcessingRef,
        processedMessageIds,
        initialLoadCompletedRef,
        processMessages: (loadedMessages, hasMore, isInitialLoad) =>
          processLoadedRoomMessages({
            loadedMessages,
            hasMore,
            isInitialLoad,
            processedMessageIds,
            setMessages,
            setHasMoreMessages,
            initialLoadCompletedRef,
          }),
        setRoom: vi.fn(),
        setMessages,
        setLoadingMessages: vi.fn(),
        setError: vi.fn(),
        setHasMoreMessages,
        cleanup: vi.fn(),
        logout: vi.fn(),
        onReplace: vi.fn(),
        handleReactionUpdate: vi.fn(),
        showRejectedMessage: vi.fn(),
      });

      return { handlers, getMessages: () => messages };
    };

    const historyFirst = createMessageFlow();
    const historyMessage = {
      _id: 'same-message',
      timestamp: '2026-07-07T00:00:00.000Z',
    };
    historyFirst.handlers.onPreviousMessagesLoaded({
      messages: [historyMessage],
      hasMore: false,
    });
    historyFirst.handlers.onMessage(historyMessage);

    const liveFirst = createMessageFlow();
    const liveMessage = {
      _id: 'same-message',
      timestamp: '2026-07-07T00:00:00.000Z',
    };
    liveFirst.handlers.onMessage(liveMessage);
    liveFirst.handlers.onPreviousMessagesLoaded({
      messages: [liveMessage],
      hasMore: false,
    });

    expect(historyFirst.getMessages()).toHaveLength(1);
    expect(liveFirst.getMessages()).toHaveLength(1);
  });

  it('batches burst messages into one chronological state update', () => {
    vi.useFakeTimers();
    try {
      const processedMessageIds = { current: new Set() };
      let committed = [];
      const setMessages = vi.fn((updater) => {
        committed = updater(committed);
      });
      const handlers = createRoomEventHandlers({
        mountedRef: { current: true },
        messageProcessingRef: { current: false },
        processedMessageIds,
        initialLoadCompletedRef: { current: true },
        processMessages: vi.fn(),
        setRoom: vi.fn(),
        setMessages,
        setLoadingMessages: vi.fn(),
        setError: vi.fn(),
        setHasMoreMessages: vi.fn(),
        cleanup: vi.fn(),
        logout: vi.fn(),
        onReplace: vi.fn(),
        handleReactionUpdate: vi.fn(),
        showRejectedMessage: vi.fn(),
      });

      handlers.onMessage({ _id: 'message-2', timestamp: 2000 });
      handlers.onMessage({ _id: 'message-1', timestamp: 1000 });
      handlers.onMessage({ _id: 'message-3', timestamp: 3000 });

      expect(setMessages).not.toHaveBeenCalled();
      vi.advanceTimersByTime(16);

      expect(setMessages).toHaveBeenCalledTimes(1);
      expect(committed.map((message) => message._id)).toEqual([
        'message-1',
        'message-2',
        'message-3',
      ]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps a read receipt that arrives before a queued message is flushed', () => {
    vi.useFakeTimers();
    try {
      let committed = [];
      const handlers = createRoomEventHandlers({
        mountedRef: { current: true },
        messageProcessingRef: { current: false },
        processedMessageIds: { current: new Set() },
        initialLoadCompletedRef: { current: true },
        processMessages: vi.fn(),
        setRoom: vi.fn(),
        setMessages: vi.fn((updater) => {
          committed = updater(committed);
        }),
        setLoadingMessages: vi.fn(),
        setError: vi.fn(),
        setHasMoreMessages: vi.fn(),
        cleanup: vi.fn(),
        logout: vi.fn(),
        onReplace: vi.fn(),
        handleReactionUpdate: vi.fn(),
        showRejectedMessage: vi.fn(),
      });

      handlers.onMessage({ _id: 'message-1', timestamp: 1000, readers: [] });
      handlers.onMessagesRead({
        userId: 'user-2',
        messageIds: ['message-1'],
        timestamp: '2026-08-11T00:00:00.000Z',
      });
      vi.advanceTimersByTime(16);

      expect(committed[0].readers).toEqual([
        { userId: 'user-2', readAt: '2026-08-11T00:00:00.000Z' },
      ]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('creates room event handlers with mounted and processing guards', () => {
    const mountedRef = { current: true };
    const messageProcessingRef = { current: false };
    const processedMessageIds = { current: new Set() };
    const initialLoadCompletedRef = { current: false };
    const setRoom = vi.fn();
    const setMessages = vi.fn();
    const setLoadingMessages = vi.fn();
    const setError = vi.fn();
    const setHasMoreMessages = vi.fn();
    const processMessages = vi.fn();
    const cleanup = vi.fn();
    const logout = vi.fn();
    const onReplace = vi.fn();
    const handleReactionUpdate = vi.fn();
    const showRejectedMessage = vi.fn();

    const handlers = createRoomEventHandlers({
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
      scheduleMessageFlush: (callback) => {
        callback();
        return null;
      },
    });

    handlers.onParticipantsUpdate([{ _id: 'user-1' }]);
    handlers.onMessagesRead({
      userId: 'user-1',
      messageIds: ['message-1'],
      timestamp: '2026-07-07T00:00:00.000Z',
    });
    handlers.onMessage({ _id: 'message-1' });
    handlers.onPreviousMessagesLoaded({
      messages: [{ _id: 'message-2' }],
      hasMore: true,
    });
    handlers.onMessageReactionUpdate({ messageId: 'message-1' });
    handlers.onSessionEnded();
    handlers.onError({ code: 'MESSAGE_REJECTED', message: 'blocked' });

    expect(setRoom).toHaveBeenCalledWith(expect.any(Function));
    expect(setMessages).toHaveBeenCalledTimes(2);
    expect(processMessages).toHaveBeenCalledWith(
      [{ _id: 'message-2' }],
      true,
      true
    );
    expect(setLoadingMessages).toHaveBeenCalledWith(false);
    expect(handleReactionUpdate).toHaveBeenCalledWith({
      messageId: 'message-1',
    });
    expect(cleanup).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledTimes(1);
    expect(onReplace).toHaveBeenCalledWith('/?error=session_expired');
    expect(showRejectedMessage).toHaveBeenCalledWith('blocked');
  });
});
