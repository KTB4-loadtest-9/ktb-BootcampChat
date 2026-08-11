import { afterEach, describe, expect, it, vi } from 'vitest';
import axios from 'axios';
import axiosInstance from '../axios';
import fileService from '../fileService';

const originalBaseUrl = fileService.baseUrl;

vi.mock('../../components/Toast', () => ({
  Toast: {
    error: vi.fn(),
  },
}));

describe('fileService', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
    fileService.baseUrl = originalBaseUrl;
  });

  it('uploads directly to S3 while preserving the existing file response contract', async () => {
    vi.stubEnv('NEXT_PUBLIC_API_URL', 'http://api.test');
    vi.stubEnv('NEXT_PUBLIC_FILE_UPLOAD_MODE', 'presigned');
    fileService.baseUrl = 'http://api.test';

    const file = new File(['image'], 'photo.png', { type: 'image/png' });
    const fileData = {
      _id: 'file-1',
      filename: 'generated.png',
      originalname: file.name,
      mimetype: file.type,
      size: file.size,
    };
    const post = vi.spyOn(axiosInstance, 'post')
      .mockResolvedValueOnce({
        data: {
          uploadId: 'upload-1',
          uploadUrl: 'https://s3.test/upload',
          objectKey: 'chat/generated.png',
          requiredHeaders: { 'Content-Type': file.type },
        },
      })
      .mockResolvedValueOnce({
        data: {
          success: true,
          file: fileData,
        },
      });
    const put = vi.spyOn(axios, 'put').mockImplementation(async (_url, _file, config) => {
      for (const loaded of [1, 5, 10, 19, 20, 100]) {
        config.onUploadProgress({ loaded, total: 100 });
      }
      return { status: 200 };
    });
    const onProgress = vi.fn();

    const result = await fileService.uploadFile(file, onProgress);

    expect(post).toHaveBeenNthCalledWith(
      1,
      'http://api.test/api/files/chat-images/presign',
      {
        originalName: file.name,
        contentType: file.type,
        size: file.size,
      },
      { withCredentials: true, timeout: 10000, maxRetries: 0 }
    );
    expect(put).toHaveBeenCalledWith(
      'https://s3.test/upload',
      file,
      expect.objectContaining({
        headers: { 'Content-Type': file.type },
        timeout: 30000,
      })
    );
    expect(post).toHaveBeenNthCalledWith(
      2,
      'http://api.test/api/files/chat-images/upload-1/complete',
      {},
      { withCredentials: true, timeout: 10000, maxRetries: 0 }
    );
    expect(onProgress.mock.calls).toEqual([[10], [20], [100]]);
    expect(result).toEqual({
      success: true,
      data: {
        success: true,
        objectKey: 'chat/generated.png',
        file: {
          ...fileData,
          url: 'http://api.test/api/files/view/generated.png',
        },
      },
    });
  });

  it('keeps the existing multipart upload when presigned mode is disabled', async () => {
    vi.stubEnv('NEXT_PUBLIC_API_URL', 'http://api.test');
    vi.stubEnv('NEXT_PUBLIC_FILE_UPLOAD_MODE', 'server');
    fileService.baseUrl = 'http://api.test';

    const file = new File(['image'], 'photo.png', { type: 'image/png' });
    const post = vi.spyOn(axiosInstance, 'post').mockResolvedValue({
      data: {
        success: true,
        file: {
          _id: 'file-1',
          filename: 'generated.png',
          originalname: file.name,
          mimetype: file.type,
          size: file.size,
        },
      },
    });
    const put = vi.spyOn(axios, 'put');

    const result = await fileService.uploadFile(file);

    expect(post).toHaveBeenCalledWith(
      'http://api.test/api/files/upload',
      expect.any(FormData),
      expect.objectContaining({
        maxRetries: 0,
        timeout: 30000,
        withCredentials: true,
      })
    );
    expect(put).not.toHaveBeenCalled();
    expect(result.success).toBe(true);
  });

  it('rejects files larger than the 5MB upload limit', async () => {
    const result = await fileService.validateFile({
      name: 'large.png',
      type: 'image/png',
      size: 5 * 1024 * 1024 + 1,
    });

    expect(result).toEqual({
      success: false,
      message: '파일 크기는 5 MB를 초과할 수 없습니다.',
    });
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
});
