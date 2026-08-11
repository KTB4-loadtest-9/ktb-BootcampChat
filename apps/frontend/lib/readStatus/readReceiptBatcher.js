import socketClient from '@/lib/socket/socketClient';

export const READ_RECEIPT_BATCH_WINDOW_MS = 16;

export const createReadReceiptBatcher = ({
  canSend,
  send,
  windowMs = READ_RECEIPT_BATCH_WINDOW_MS,
}) => {
  let pendingByMessageId = new Map();
  let timerId = null;

  const flush = async () => {
    if (timerId !== null) {
      clearTimeout(timerId);
      timerId = null;
    }

    if (pendingByMessageId.size === 0) return [];

    const currentBatch = pendingByMessageId;
    pendingByMessageId = new Map();
    const messageIds = [...currentBatch.keys()];

    try {
      if (!canSend()) {
        throw new Error('Socket not connected');
      }

      await send(messageIds);

      for (const waiters of currentBatch.values()) {
        for (const { resolve } of waiters) resolve(messageIds);
      }
      return messageIds;
    } catch (error) {
      for (const waiters of currentBatch.values()) {
        for (const { reject } of waiters) reject(error);
      }
      throw error;
    }
  };

  const scheduleFlush = () => {
    if (timerId !== null) return;

    timerId = setTimeout(() => {
      flush().catch(() => {
        // 각 enqueue Promise가 실패를 전달하므로 timer에서는 중복 throw하지 않는다.
      });
    }, windowMs);
  };

  const enqueue = messageId => {
    if (!messageId) {
      return Promise.reject(new Error('messageId is required'));
    }

    return new Promise((resolve, reject) => {
      const waiters = pendingByMessageId.get(messageId) || [];
      waiters.push({ resolve, reject });
      pendingByMessageId.set(messageId, waiters);
      scheduleFlush();
    });
  };

  return {
    enqueue,
    flush,
  };
};

const readReceiptBatcher = createReadReceiptBatcher({
  canSend: () => socketClient.canSend(),
  send: messageIds => socketClient.markMessagesAsRead(messageIds),
});

export const enqueueReadReceipt = messageId => (
  readReceiptBatcher.enqueue(messageId)
);

export const flushReadReceipts = () => readReceiptBatcher.flush();
