import { afterEach, describe, expect, it, vi } from 'vitest';
import fileService from '../fileService';
import axiosInstance from '../axios';
import axios from 'axios';

vi.mock('../axios', () => ({
  default: { post: vi.fn() },
}));

vi.mock('axios', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    default: { ...actual.default, put: vi.fn() },
  };
});

vi.mock('../../components/Toast', () => ({
  Toast: {
    error: vi.fn(),
  },
}));

describe('fileService', () => {
  const originalDirectUploadEnabled = fileService.directImageUploadEnabled;

  afterEach(() => {
    vi.restoreAllMocks();
    fileService.directImageUploadEnabled = originalDirectUploadEnabled;
  });

  it('handles upload size limit errors without logging console errors', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    const result = fileService.handleUploadError(
      Object.assign(new Error('파일 크기는 5MB를 초과할 수 없습니다.'), {
        status: 413,
      })
    );

    expect(result).toEqual({
      success: false,
      message: '파일 크기는 5MB를 초과할 수 없습니다.',
    });
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('routes images to direct upload when enabled', async () => {
    fileService.directImageUploadEnabled = true;
    const direct = vi.spyOn(fileService, 'uploadChatImageDirect').mockResolvedValue({ success: true });
    const legacy = vi.spyOn(fileService, 'uploadFile');
    const image = { type: 'image/png', name: 'photo.png' };
    await fileService.uploadFile(image, vi.fn());
    expect(direct).toHaveBeenCalled();
    expect(legacy).toHaveBeenCalled();
  });

  it('keeps PDFs on the existing upload API', async () => {
    fileService.directImageUploadEnabled = true;
    const direct = vi.spyOn(fileService, 'uploadChatImageDirect').mockResolvedValue({ success: true });
    const legacy = vi.spyOn(fileService, 'uploadFile').mockResolvedValue({ success: true });
    const pdf = { type: 'application/pdf', name: 'guide.pdf' };
    await fileService.uploadChatFile(pdf, vi.fn(), 'token', 'session');
    expect(legacy).toHaveBeenCalledWith(pdf, expect.any(Function), 'token', 'session');
    expect(direct).not.toHaveBeenCalled();
  });

  it('completes a direct chat image through the legacy upload URI', async () => {
    fileService.directImageUploadEnabled = true;
    const image = new File(['png'], 'photo.png', { type: 'image/png' });
    axiosInstance.post
      .mockResolvedValueOnce({
        data: {
          uploadId: 'upload-1',
          uploadUrl: 'https://signed.invalid/upload',
          requiredHeaders: { 'Content-Type': 'image/png' },
        },
      })
      .mockResolvedValueOnce({ data: { file: { _id: 'file-1' } } });
    axios.put.mockResolvedValue({ status: 200 });

    const result = await fileService.uploadChatImageDirect(image);

    expect(result.success).toBe(true);
    expect(axiosInstance.post).toHaveBeenNthCalledWith(2, '/api/files/upload', {
      uploadId: 'upload-1',
      uploadType: 'PRESIGNED_CHAT_IMAGE',
    });
  });
});
