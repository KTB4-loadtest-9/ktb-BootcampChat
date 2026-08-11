import React from 'react';
import { useRouter } from 'next/router';
import { withAuth } from '@/contexts/AuthContext';
import ChatRoomsView from '@/features/chat/rooms/ChatRoomsView';

const ChatPage = () => {
  const router = useRouter();

  return <ChatRoomsView router={router} />;
};

export default withAuth(ChatPage);
