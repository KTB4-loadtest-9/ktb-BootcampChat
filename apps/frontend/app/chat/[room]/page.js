'use client';

import { Profiler, useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import ChatRoomView from '@/features/chat/room/ChatRoomView';
import { useRoomId } from '@/hooks/useRoomId';
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

export default function ChatRoomPage() {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, isLoading } = useAuth();
  const roomId = useRoomId();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      router.replace(`/?redirect=${pathname}`);
    }
  }, [isAuthenticated, isLoading, pathname, router]);

  if (isLoading || !isAuthenticated) {
    return <LoadingState />;
  }

  const content = (
    <>
      <ChatHeader />
      <ChatRoomView
        roomId={roomId}
        onNavigate={router.push}
        onReplace={router.replace}
        asPath={pathname}
      />
    </>
  );

  if (isChatRenderProfilingEnabled) {
    return (
      <Profiler id="ChatRoomPage" onRender={recordChatRender}>
        {content}
      </Profiler>
    );
  }

  return content;
}
