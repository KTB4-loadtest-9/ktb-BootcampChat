package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.HandshakeData;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.netty.handler.codec.http.HttpHeaders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private SocketIOClient client;
    @Mock private SocketIOClient existingClient;
    @Mock private HandshakeData handshakeData;
    @Mock private HttpHeaders httpHeaders;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomJoinHandler,
                roomLeaveHandler,
                new SimpleMeterRegistry());
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        SocketUser user = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleReconnectRooms(client, Set.of("room-1", "room-2"));
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list"));
    }

    @Test
    void onConnect_withExistingConnection_completesInitialization() {
        String existingSocketId = UUID.randomUUID().toString();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", "socket-1");
        SocketUser existingUser = new SocketUser(user.id(), "tester", "session-0", existingSocketId);
        when(connectedUsers.get(user.id())).thenReturn(existingUser);
        when(socketIOServer.getClient(UUID.fromString(existingSocketId))).thenReturn(existingClient);
        when(client.getHandshakeData()).thenReturn(handshakeData);
        when(handshakeData.getHttpHeaders()).thenReturn(httpHeaders);
        when(httpHeaders.get("User-Agent")).thenReturn("test-agent");
        when(client.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8080));
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleReconnectRooms(client, Set.of("room-1"));
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list"));
    }

    @Test
    void onDisconnect_removesCurrentConnectionAndLeavesRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(roomLeaveHandler).handleDisconnectRooms(client, Set.of("room-1"));
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of("user:" + user.id(), "room-list"));
        verify(client).del("user");
        verify(client).disconnect();
    }

    @Test
    void onDisconnect_skipsRoomCleanupForAnOlderConnection() {
        UUID oldSocketId = UUID.randomUUID();
        SocketUser oldUser = new SocketUser("user-1", "tester", "session-1", oldSocketId.toString());
        SocketUser activeUser = new SocketUser("user-1", "tester", "session-1", UUID.randomUUID().toString());
        when(client.get("user")).thenReturn(oldUser);
        when(client.getSessionId()).thenReturn(oldSocketId);
        when(connectedUsers.get(oldUser.id())).thenReturn(activeUser);

        handler.onDisconnect(client);

        verify(roomLeaveHandler, org.mockito.Mockito.never()).handleDisconnectRooms(org.mockito.Mockito.any(), org.mockito.Mockito.any());
        verify(connectedUsers, org.mockito.Mockito.never()).del(oldUser.id());
        verify(client).disconnect();
    }
}
