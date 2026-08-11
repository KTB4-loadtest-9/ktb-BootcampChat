import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import NewChatRoom from '../../pages/chat/new';

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  push: vi.fn(),
}));

vi.mock('next/router', () => ({
  useRouter: () => ({ push: mocks.push }),
}));
vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { token: 'token-1' } }),
}));
vi.mock('@/lib/api/client', () => ({
  default: { post: mocks.post },
}));

describe('New chat room page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.post.mockResolvedValue({ data: { data: { _id: 'room-1' } } });
    mocks.push.mockResolvedValue(true);
  });

  it('navigates with the creator membership returned by room creation', async () => {
    render(<NewChatRoom />);

    fireEvent.change(screen.getByTestId('chat-room-name-input'), {
      target: { value: 'load-test-room' },
    });
    fireEvent.click(screen.getByTestId('create-chat-room-button'));

    await waitFor(() => expect(mocks.push).toHaveBeenCalledWith('/chat/room-1'));
    expect(mocks.post).toHaveBeenCalledTimes(1);
    expect(mocks.post).toHaveBeenCalledWith('/api/rooms', {
      name: 'load-test-room',
      password: undefined,
    });
  });
});
