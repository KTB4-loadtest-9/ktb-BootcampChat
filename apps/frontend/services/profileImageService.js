import axiosInstance from './axios';
import { loadStoredUser } from '@/lib/auth/authStorage';

class ProfileImageService {
  constructor() {
    this.cache = new Map();
    this.pending = new Map();
    this.flushScheduled = false;
  }

  invalidate(userId) {
    if (userId) this.cache.delete(userId);
  }

  async getUrl(user, explicitUrl) {
    if (explicitUrl) return explicitUrl;
    const imageValue = user?.profileImage;
    if (!imageValue) return '';

    const userId = user?.id || user?._id;
    if (!userId) {
      return imageValue.startsWith('http') ? imageValue : this.authenticatedLocalUrl(imageValue);
    }
    const cached = this.cache.get(userId);
    if (cached && cached.expiresAt - Date.now() > 30000) return cached.url;

    return new Promise((resolve, reject) => {
      const waiters = this.pending.get(userId) || [];
      waiters.push({ resolve, reject });
      this.pending.set(userId, waiters);
      if (!this.flushScheduled) {
        this.flushScheduled = true;
        queueMicrotask(() => this.flush());
      }
    });
  }

  async flush() {
    this.flushScheduled = false;
    const pending = this.pending;
    this.pending = new Map();
    const userIds = [...pending.keys()];
    try {
      const response = await axiosInstance.post('/api/users/profile-images/access-urls', { userIds });
      const byId = new Map((response.data?.items || []).map((item) => [item.userId, item]));
      userIds.forEach((userId) => {
        const item = byId.get(userId);
        if (item?.url) {
          const url = item.url.startsWith('http') ? item.url : this.authenticatedLocalUrl(item.url);
          const expiresAt = new Date(item.expiresAt).getTime();
          this.cache.set(userId, { url, expiresAt });
          pending.get(userId).forEach(({ resolve }) => resolve(url));
        } else {
          pending.get(userId).forEach(({ resolve }) => resolve(''));
        }
      });
    } catch (error) {
      pending.forEach((waiters) => waiters.forEach(({ reject }) => reject(error)));
    }
  }

  authenticatedLocalUrl(path) {
    const base = process.env.NEXT_PUBLIC_API_URL ||
      (typeof window !== 'undefined' ? window.location.origin : undefined);
    if (!base) return path;
    const url = new URL(path, base);
    const session = loadStoredUser();
    if (session?.token) url.searchParams.set('token', session.token);
    if (session?.sessionId) url.searchParams.set('sessionId', session.sessionId);
    return url.toString();
  }
}

const profileImageService = new ProfileImageService();

export default profileImageService;
