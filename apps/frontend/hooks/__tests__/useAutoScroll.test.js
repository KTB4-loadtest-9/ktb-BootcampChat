import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useAutoScroll } from '../useAutoScroll';

describe('useAutoScroll', () => {
  it('does not jump to the bottom when an older page is prepended', () => {
    const scrollTo = vi.fn();
    const container = {
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      scrollTo,
      scrollHeight: 600,
      scrollTop: 100,
      clientHeight: 300,
    };
    const { result, rerender } = renderHook(
      ({ messages }) => useAutoScroll(messages, 'me', false),
      { initialProps: { messages: [] } }
    );

    act(() => {
      result.current.containerRef.current = container;
    });
    rerender({
      messages: [
        { _id: 'message-2', timestamp: 2000, sender: { _id: 'other' } },
        { _id: 'message-3', timestamp: 3000, sender: { _id: 'other' } },
      ],
    });
    scrollTo.mockClear();

    rerender({
      messages: [
        { _id: 'message-1', timestamp: 1000, sender: { _id: 'other' } },
        { _id: 'message-2', timestamp: 2000, sender: { _id: 'other' } },
        { _id: 'message-3', timestamp: 3000, sender: { _id: 'other' } },
      ],
    });

    expect(scrollTo).not.toHaveBeenCalled();
  });
});
