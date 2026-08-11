const READ_VISIBILITY_THRESHOLD = 0.5;

const callbacksByElement = new Map();
let sharedObserver = null;

const handleIntersections = entries => {
  for (const entry of entries) {
    if (
      !entry.isIntersecting ||
      entry.intersectionRatio < READ_VISIBILITY_THRESHOLD
    ) {
      continue;
    }

    const callbacks = callbacksByElement.get(entry.target);
    if (!callbacks) continue;

    for (const callback of [...callbacks]) {
      callback();
    }
  }
};

const getSharedObserver = () => {
  if (sharedObserver || typeof IntersectionObserver === 'undefined') {
    return sharedObserver;
  }

  sharedObserver = new IntersectionObserver(handleIntersections, {
    root: null,
    rootMargin: '0px',
    threshold: READ_VISIBILITY_THRESHOLD,
  });

  return sharedObserver;
};

export const observeReadVisibility = (element, callback) => {
  if (!element || typeof callback !== 'function') {
    return () => {};
  }

  const observer = getSharedObserver();
  if (!observer) return () => {};

  let callbacks = callbacksByElement.get(element);
  if (!callbacks) {
    callbacks = new Set();
    callbacksByElement.set(element, callbacks);
    observer.observe(element);
  }
  callbacks.add(callback);

  let active = true;
  return () => {
    if (!active) return;
    active = false;

    const currentCallbacks = callbacksByElement.get(element);
    currentCallbacks?.delete(callback);

    if (currentCallbacks?.size === 0) {
      callbacksByElement.delete(element);
      observer.unobserve(element);
    }

    if (callbacksByElement.size === 0 && sharedObserver === observer) {
      observer.disconnect();
      sharedObserver = null;
    }
  };
};
