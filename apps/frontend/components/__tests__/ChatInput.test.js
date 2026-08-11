import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatInput from '../ChatInput';
import fileService from '@/services/fileService';

describe('ChatInput', () => {
  it('renders the lazy emoji picker under React 19', async () => {
    const { container, getByLabelText } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    fireEvent.click(getByLabelText('이모티콘'));

    await waitFor(() => {
      expect(container.querySelector('em-emoji-picker')).toBeInTheDocument();
    });
  });

  it('keeps the message until the socket-confirmed submit completes', async () => {
    let confirmSubmit;
    const onSubmit = vi.fn(() => new Promise((resolve) => {
      confirmSubmit = resolve;
    }));
    const { getByTestId } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
        onSubmit={onSubmit}
      />
    );

    fireEvent.change(getByTestId('chat-message-input'), {
      target: { value: 'hello' },
    });
    fireEvent.click(getByTestId('chat-send-button'));

    expect(getByTestId('chat-message-input')).toHaveValue('hello');
    expect(getByTestId('message-submission-status')).toHaveTextContent('submitting');

    confirmSubmit();
    await waitFor(() => {
      expect(getByTestId('message-submission-status')).toHaveTextContent('complete');
    });
    expect(getByTestId('chat-message-input')).toHaveValue('');
  });

  it('resets a completed submission when the next message is edited', async () => {
    const { getByTestId } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: 'first' } });
    fireEvent.click(getByTestId('chat-send-button'));
    await waitFor(() => {
      expect(getByTestId('message-submission-status')).toHaveTextContent('complete');
    });

    fireEvent.change(input, { target: { value: 'second' } });

    expect(getByTestId('message-submission-status')).toHaveTextContent('idle');
  });

  it('does not submit while Enter is completing IME composition', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
        onSubmit={onSubmit}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: '안녕하세요' } });
    fireEvent.keyDown(input, {
      key: 'Enter',
      code: 'Enter',
      keyCode: 229,
      isComposing: true,
    });

    expect(onSubmit).not.toHaveBeenCalled();
    expect(input).toHaveValue('안녕하세요');
  });

  it('does not select a file rejected by validation', async () => {
    const onFileSelect = vi.fn();
    vi.spyOn(fileService, 'validateFile').mockResolvedValueOnce({
      success: false,
      message: '지원하지 않는 파일 형식입니다.',
    });
    const { getByTestId } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
        onFileSelect={onFileSelect}
      />
    );

    fireEvent.change(getByTestId('file-upload-input'), {
      target: {
        files: [new File(['bad'], 'bad.exe', { type: 'application/octet-stream' })],
      },
    });

    await waitFor(() => {
      expect(fileService.validateFile).toHaveBeenCalled();
    });
    expect(onFileSelect).not.toHaveBeenCalled();
  });

  it('keeps a selected file until its socket-confirmed submit completes', async () => {
    let confirmSubmit;
    const onSubmit = vi.fn(() => new Promise((resolve) => {
      confirmSubmit = resolve;
    }));
    vi.spyOn(fileService, 'validateFile').mockResolvedValueOnce({ success: true });
    const { getByTestId } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
        onSubmit={onSubmit}
      />
    );

    fireEvent.change(getByTestId('file-upload-input'), {
      target: {
        files: [new File(['image'], 'image.png', { type: 'image/png' })],
      },
    });
    await waitFor(() => expect(getByTestId('chat-send-button')).toBeEnabled());
    fireEvent.click(getByTestId('chat-send-button'));

    expect(getByTestId('message-submission-status')).toHaveTextContent('submitting');

    confirmSubmit();
    await waitFor(() => {
      expect(getByTestId('message-submission-status')).toHaveTextContent('complete');
    });
  });
});
