import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import socketClient from '@/lib/socket/socketClient';
import { useRoomsSocket } from '../useRoomsSocket';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    connect: vi.fn(),
  },
}));

const currentUser = {
  token: 'token-1',
  sessionId: 'session-1',
};

const renderRoomsSocket = (socket, overrides = {}) => {
  socketClient.connect.mockResolvedValue(socket);

  return renderHook(() =>
    useRoomsSocket({
      currentUser,
      router: { push: vi.fn() },
      setConnectionStatus: vi.fn(),
      rooms: [],
      setRooms: vi.fn(),
      setMetadata: vi.fn(),
      ...overrides,
    })
  );
};

const createSocket = () => ({
  on: vi.fn(),
  emit: vi.fn(),
  disconnect: vi.fn(),
});

const handlerFor = (socket, event) =>
  socket.on.mock.calls.find(([registered]) => registered === event)[1];

describe('useRoomsSocket', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not emit joinRoomList because the server joins room-list on connect', async () => {
    const socket = {
      on: vi.fn(),
      emit: vi.fn(),
      disconnect: vi.fn(),
    };

    renderRoomsSocket(socket);

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('connect', expect.any(Function));
    });

    const connectHandler = socket.on.mock.calls.find(([event]) => event === 'connect')[1];
    connectHandler();

    expect(socket.emit).not.toHaveBeenCalledWith('joinRoomList');
  });

  it('does not register roomDeleted without a server-side room delete event', async () => {
    const socket = {
      on: vi.fn(),
      emit: vi.fn(),
      disconnect: vi.fn(),
    };

    renderRoomsSocket(socket);

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalled();
    });

    const registeredEvents = socket.on.mock.calls.map(([event]) => event);
    expect(registeredEvents).not.toContain('roomDeleted');
  });

  it('merges a roomActivity update into the matching room without dropping its other fields', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();

    renderRoomsSocket(socket, { setRooms });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomActivity', expect.any(Function));
    });

    handlerFor(socket, 'roomActivity')({ _id: 'room-2', recentMessageCount: 9 });

    const updateRooms = setRooms.mock.calls[0][0];

    expect(
      updateRooms([
        { _id: 'room-1', name: '방1', recentMessageCount: 1 },
        { _id: 'room-2', name: '방2', recentMessageCount: 2 },
      ])
    ).toEqual([
      { _id: 'room-1', name: '방1', recentMessageCount: 1 },
      { _id: 'room-2', name: '방2', recentMessageCount: 9 },
    ]);
  });

  it('prepends a new room and updates pagination metadata once', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();
    const setMetadata = vi.fn();

    renderRoomsSocket(socket, {
      rooms: [{ _id: 'room-1', name: '방 1' }],
      setRooms,
      setMetadata,
    });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomCreated', expect.any(Function));
    });

    handlerFor(socket, 'roomCreated')({ _id: 'room-2', name: '방 2 최신' });

    const updateRooms = setRooms.mock.calls[0][0];

    expect(updateRooms([
      { _id: 'room-1', name: '방 1' },
    ])).toEqual([
      { _id: 'room-2', name: '방 2 최신' },
      { _id: 'room-1', name: '방 1' },
    ]);

    const updateMetadata = setMetadata.mock.calls[0][0];
    expect(updateMetadata({
      total: 20,
      page: 0,
      pageSize: 20,
      totalPages: 1,
      hasMore: false,
      currentCount: 20,
    })).toMatchObject({ total: 21, totalPages: 2, hasMore: true });
  });

  it('does not change metadata when a roomCreated event repeats an already loaded room', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();
    const setMetadata = vi.fn();

    renderRoomsSocket(socket, {
      rooms: [{ _id: 'room-2', name: '방 2', participants: [{ id: 'user-1' }] }],
      setRooms,
      setMetadata,
    });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomCreated', expect.any(Function));
    });

    handlerFor(socket, 'roomCreated')({ _id: 'room-2', name: '방 2 최신' });

    expect(setMetadata).not.toHaveBeenCalled();
  });

  it('merges roomUpdated payloads without discarding fields from the paged list', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();

    renderRoomsSocket(socket, { setRooms });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomUpdated', expect.any(Function));
    });

    handlerFor(socket, 'roomUpdated')({ _id: 'room-2', name: '방 2 최신' });

    const updateRooms = setRooms.mock.calls[0][0];

    expect(updateRooms([
      { _id: 'room-1', name: '방 1' },
      { _id: 'room-2', name: '방 2', participants: [{ id: 'user-1' }] },
    ])).toEqual([
      { _id: 'room-1', name: '방 1' },
      { _id: 'room-2', name: '방 2 최신', participants: [{ id: 'user-1' }] },
    ]);
  });

  it('ignores a roomActivity payload without a room id', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();

    renderRoomsSocket(socket, { setRooms });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomActivity', expect.any(Function));
    });

    handlerFor(socket, 'roomActivity')(undefined);

    expect(setRooms).not.toHaveBeenCalled();
  });
});
