import { afterEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '../axios';
import profileImageService from '../profileImageService';

vi.mock('../axios', () => ({
  default: { post: vi.fn() },
}));

vi.mock('@/lib/auth/authStorage', () => ({
  loadStoredUser: vi.fn(() => ({ token: 'token', sessionId: 'session' })),
}));

describe('profileImageService', () => {
  afterEach(() => {
    vi.clearAllMocks();
    profileImageService.cache.clear();
    profileImageService.pending.clear();
    profileImageService.flushScheduled = false;
  });

  it('batches profile image access URL requests and caches signed URLs', async () => {
    axiosInstance.post.mockResolvedValue({
      data: {
        items: [{
          userId: 'user-1',
          url: 'https://signed.example/image',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
          error: null,
        }],
      },
    });

    const user = { id: 'user-1', profileImage: 'profiles/user-1/image.png' };
    const [first, second] = await Promise.all([
      profileImageService.getUrl(user),
      profileImageService.getUrl(user),
    ]);

    expect(first).toBe('https://signed.example/image');
    expect(second).toBe(first);
    expect(axiosInstance.post).toHaveBeenCalledTimes(1);
    expect(axiosInstance.post).toHaveBeenCalledWith('/api/users/profile-images/access-urls', {
      userIds: ['user-1'],
    });
  });
});
