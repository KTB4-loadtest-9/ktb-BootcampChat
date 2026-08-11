import React, { useState } from 'react';
import { LockIcon, GroupIcon } from '@vapor-ui/icons';
import { Button, Text, TextInput, VStack, HStack } from '@vapor-ui/core';
import * as Table from '@/components/Table';

const RoomsTable = ({
  rooms,
  canJoinRooms,
  joiningRoomId,
  joinError,
  onClearJoinError,
  onJoinRoom,
}) => {
  const [passwordRoomId, setPasswordRoomId] = useState(null);
  const [password, setPassword] = useState('');

  if (!rooms || rooms.length === 0) return null;

  const openPasswordPrompt = (roomId) => {
    onClearJoinError(roomId);
    setPasswordRoomId(roomId);
    setPassword('');
  };

  const closePasswordPrompt = () => {
    onClearJoinError(passwordRoomId);
    setPasswordRoomId(null);
    setPassword('');
  };

  const submitPassword = async (event, roomId) => {
    event.preventDefault();

    if (!password) return;

    const joined = await onJoinRoom(roomId, password);
    if (joined) {
      setPasswordRoomId(null);
      setPassword('');
    }
  };

  const isJoiningAnyRoom = joiningRoomId !== null;

  return (
    <div
      className="chat-rooms-table"
      style={{
        height: '430px',
        overflowY: 'auto',
        position: 'relative',
        borderRadius: '0.5rem',
        backgroundColor: 'var(--background-normal)',
        border: '1px solid var(--border-color)',
        scrollBehavior: 'smooth',
        WebkitOverflowScrolling: 'touch',
      }}
    >
      <Table.Root style={{ width: '100%' }}>
        <Table.ColumnGroup>
          <Table.Column style={{ width: '40%' }} />
          <Table.Column style={{ width: '12%' }} />
          <Table.Column style={{ width: '12%' }} />
          <Table.Column style={{ width: '21%' }} />
          <Table.Column style={{ width: '15%' }} />
        </Table.ColumnGroup>

        <Table.Header>
          <Table.Row>
            <Table.Heading>채팅방</Table.Heading>
            <Table.Heading>참여자</Table.Heading>
            <Table.Heading>최근 메시지</Table.Heading>
            <Table.Heading>생성일</Table.Heading>
            <Table.Heading>액션</Table.Heading>
          </Table.Row>
        </Table.Header>

        <Table.Body>
          {rooms.map((room) => {
            const isJoining = joiningRoomId === room._id;
            const isPasswordPromptOpen = passwordRoomId === room._id;
            const roomJoinError = joinError?.roomId === room._id
              ? joinError.message
              : null;

            return (
              <React.Fragment key={room._id}>
                <Table.Row>
                  <Table.Cell>
                    <VStack $css={{ gap: '$050', alignItems: 'flex-start' }}>
                      <Text style={{ fontWeight: 500 }}>{room.name}</Text>
                      {room.hasPassword && (
                        <HStack $css={{ gap: '$050', alignItems: 'center', color: '$warning-100' }}>
                          <LockIcon size={16} />
                          <Text typography="body3" foreground="warning-100">
                            비밀번호 필요
                          </Text>
                        </HStack>
                      )}
                    </VStack>
                  </Table.Cell>
                  <Table.Cell>
                    <HStack $css={{ gap: '$050', alignItems: 'center' }}>
                      <GroupIcon />
                      <Text typography="body2">
                        {room.participantsCount ?? room.participants?.length ?? 0}
                      </Text>
                    </HStack>
                  </Table.Cell>
                  <Table.Cell>
                    {room.recentMessageCount > 0 ? room.recentMessageCount : '-'}
                  </Table.Cell>
                  <Table.Cell>
                    <time dateTime={new Date(room.createdAt).toISOString()}>
                      {new Date(room.createdAt).toLocaleString('ko-KR', {
                        year: 'numeric',
                        month: '2-digit',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </time>
                  </Table.Cell>
                  <Table.Cell>
                    <VStack $css={{ gap: '$100', alignItems: 'flex-start' }}>
                      <Button
                        colorPalette="primary"
                        size="md"
                        onClick={() => (
                          room.hasPassword
                            ? openPasswordPrompt(room._id)
                            : onJoinRoom(room._id)
                        )}
                        disabled={!canJoinRooms || isJoiningAnyRoom}
                        aria-expanded={room.hasPassword ? isPasswordPromptOpen : undefined}
                        data-testid={room.hasPassword
                          ? 'password-room-prompt-button'
                          : 'join-chat-room-button'}
                      >
                        {isJoining
                          ? '입장 중...'
                          : room.hasPassword
                            ? '비밀번호 입력'
                            : '입장'}
                      </Button>
                      {!room.hasPassword && roomJoinError && (
                        <Text
                          typography="body3"
                          foreground="danger-100"
                          role="alert"
                        >
                          {roomJoinError}
                        </Text>
                      )}
                    </VStack>
                  </Table.Cell>
                </Table.Row>

                {isPasswordPromptOpen && (
                  <Table.Row>
                    <Table.Cell colSpan={5}>
                      <form onSubmit={(event) => submitPassword(event, room._id)}>
                        <VStack $css={{ gap: '$100', alignItems: 'stretch' }}>
                          <HStack $css={{ gap: '$100', alignItems: 'center' }}>
                            <TextInput
                              type="password"
                              size="md"
                              placeholder="채팅방 비밀번호"
                              value={password}
                              onChange={(event) => {
                                setPassword(event.target.value);
                                if (roomJoinError) {
                                  onClearJoinError(room._id);
                                }
                              }}
                              disabled={isJoiningAnyRoom}
                              autoFocus
                              aria-label={`${room.name} 비밀번호`}
                              data-testid="room-password-input"
                            />
                            <Button
                              type="submit"
                              size="md"
                              disabled={!canJoinRooms || isJoiningAnyRoom || !password}
                              data-testid="password-room-join-button"
                            >
                              {isJoining ? '입장 중...' : '입장'}
                            </Button>
                            <Button
                              type="button"
                              variant="outline"
                              size="md"
                              onClick={closePasswordPrompt}
                              disabled={isJoiningAnyRoom}
                            >
                              취소
                            </Button>
                          </HStack>
                          {roomJoinError && (
                            <Text
                              typography="body3"
                              foreground="danger-100"
                              role="alert"
                            >
                              {roomJoinError}
                            </Text>
                          )}
                        </VStack>
                      </form>
                    </Table.Cell>
                  </Table.Row>
                )}
              </React.Fragment>
            );
          })}
        </Table.Body>
      </Table.Root>
    </div>
  );
};

export default RoomsTable;
