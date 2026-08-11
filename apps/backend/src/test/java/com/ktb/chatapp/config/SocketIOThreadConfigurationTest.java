package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SocketIOThreadConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "socketio.server.host=localhost",
                    "socketio.server.port=5002",
                    "socketio.server.origin=*",
                    "socketio.server.boss-threads=1",
                    "socketio.server.worker-threads=32");

    @Test
    void socketServerUsesConfiguredNettyThreadCounts() {
        contextRunner.run(context -> {
            SocketIOConfig config = context.getAutowireCapableBeanFactory().createBean(SocketIOConfig.class);

            var meterRegistry = new SimpleMeterRegistry();
            try {
                var server = config.socketIOServer(
                        (token, client) -> AuthTokenResult.AuthTokenResultSuccess,
                        meterRegistry,
                        new MemoryStoreFactory());

                assertAll(
                        () -> assertEquals(1, server.getConfiguration().getBossThreads()),
                        () -> assertEquals(32, server.getConfiguration().getWorkerThreads()));
            } finally {
                meterRegistry.close();
            }
        });
    }
}
