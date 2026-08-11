const getMessageTimestamp = (message) => {
  const timestamp = message?.timestamp;
  if (typeof timestamp === 'number') return timestamp;
  if (timestamp instanceof Date) return timestamp.getTime();
  if (!timestamp) return 0;

  const parsedTimestamp = Date.parse(timestamp);
  return Number.isNaN(parsedTimestamp) ? 0 : parsedTimestamp;
};

const compareMessagesByTimestamp = (left, right) =>
  getMessageTimestamp(left) - getMessageTimestamp(right);

const ensureChronologicalOrder = (messages) => {
  for (let index = 1; index < messages.length; index += 1) {
    if (compareMessagesByTimestamp(messages[index - 1], messages[index]) > 0) {
      return [...messages].sort(compareMessagesByTimestamp);
    }
  }

  return messages;
};

export const collectUniqueMessages = (incomingMessages, processedMessageIds) => {
  if (!Array.isArray(incomingMessages)) {
    throw new Error('Invalid messages format');
  }

  let nextProcessedMessageIds = processedMessageIds;
  const messages = [];

  for (const message of incomingMessages) {
    if (!message?._id || nextProcessedMessageIds.has(message._id)) {
      continue;
    }

    if (nextProcessedMessageIds === processedMessageIds) {
      nextProcessedMessageIds = new Set(processedMessageIds);
    }
    nextProcessedMessageIds.add(message._id);
    messages.push(message);
  }

  return { messages, processedMessageIds: nextProcessedMessageIds };
};

export const mergeSortedMessages = (currentMessages, incomingMessages) => {
  if (incomingMessages.length === 0) {
    return currentMessages;
  }

  const sortedIncomingMessages = ensureChronologicalOrder(incomingMessages);
  if (currentMessages.length === 0) {
    return sortedIncomingMessages;
  }

  const currentFirst = currentMessages[0];
  const currentLast = currentMessages[currentMessages.length - 1];
  const incomingFirst = sortedIncomingMessages[0];
  const incomingLast = sortedIncomingMessages[sortedIncomingMessages.length - 1];

  if (compareMessagesByTimestamp(incomingLast, currentFirst) <= 0) {
    return [...sortedIncomingMessages, ...currentMessages];
  }

  if (compareMessagesByTimestamp(currentLast, incomingFirst) <= 0) {
    return [...currentMessages, ...sortedIncomingMessages];
  }

  const mergedMessages = [];
  let currentIndex = 0;
  let incomingIndex = 0;

  while (
    currentIndex < currentMessages.length &&
    incomingIndex < sortedIncomingMessages.length
  ) {
    if (
      compareMessagesByTimestamp(
        currentMessages[currentIndex],
        sortedIncomingMessages[incomingIndex]
      ) <= 0
    ) {
      mergedMessages.push(currentMessages[currentIndex]);
      currentIndex += 1;
    } else {
      mergedMessages.push(sortedIncomingMessages[incomingIndex]);
      incomingIndex += 1;
    }
  }

  return [
    ...mergedMessages,
    ...currentMessages.slice(currentIndex),
    ...sortedIncomingMessages.slice(incomingIndex),
  ];
};

export const deriveUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  const {
    messages: uniqueIncomingMessages,
    processedMessageIds: nextProcessedMessageIds,
  } = collectUniqueMessages(incomingMessages, processedMessageIds);

  return {
    messages: mergeSortedMessages(
      ensureChronologicalOrder(currentMessages),
      uniqueIncomingMessages
    ),
    processedMessageIds: nextProcessedMessageIds,
  };
};

export const mergeUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  return deriveUniqueSortedMessages(
    currentMessages,
    incomingMessages,
    processedMessageIds
  ).messages;
};
