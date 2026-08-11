import React, { Profiler } from 'react';
import { useRouter } from 'next/router';
import { withAuth } from '@/contexts/AuthContext';
import ChatRoomView from '@/features/chat/room/ChatRoomView';
import {
  isChatRenderProfilingEnabled,
  recordChatRender,
} from '@/lib/performance/chatRenderProfiler';

const LoadingState = () => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      height: '100vh',
      backgroundColor: 'var(--vapor-color-background)',
      color: 'var(--vapor-color-text-primary)',
    }}
  >
    <div>Loading...</div>
  </div>
);

const ChatRoomPage = () => {
  const router = useRouter();
  const roomParam = router.query.room;
  const roomId = Array.isArray(roomParam) ? roomParam[0] : roomParam;

  if (!router.isReady || !roomId) {
    return <LoadingState />;
  }

  const content = (
    <ChatRoomView
      roomId={roomId}
      onNavigate={router.push}
      onReplace={router.replace}
      asPath={router.asPath}
    />
  );

  if (isChatRenderProfilingEnabled) {
    return (
      <Profiler id="ChatRoomPage" onRender={recordChatRender}>
        {content}
      </Profiler>
    );
  }

  return content;
};

export default withAuth(ChatRoomPage);
