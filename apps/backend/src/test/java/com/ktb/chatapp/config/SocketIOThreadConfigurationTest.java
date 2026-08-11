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
                    "socketio.server.origin=*");

    @Test
    void socketServerUsesConfiguredNettyThreadCounts() {
        assertThreadCounts(
                contextRunner.withPropertyValues(
                        "socketio.server.boss-threads=2",
                        "socketio.server.worker-threads=4"),
                2,
                4);
    }

    @Test
    void socketServerUsesDocumentedDefaultsWhenThreadPropertiesAreAbsent() {
        assertThreadCounts(contextRunner, 1, 32);
    }

    private void assertThreadCounts(ApplicationContextRunner runner, int expectedBossThreads, int expectedWorkerThreads) {
        runner.run(context -> {
            SocketIOConfig config = context.getAutowireCapableBeanFactory().createBean(SocketIOConfig.class);

            var meterRegistry = new SimpleMeterRegistry();
            try {
                var server = config.socketIOServer(
                        (token, client) -> AuthTokenResult.AuthTokenResultSuccess,
                        meterRegistry,
                        new MemoryStoreFactory());

                assertAll(
                        () -> assertEquals(expectedBossThreads, server.getConfiguration().getBossThreads()),
                        () -> assertEquals(expectedWorkerThreads, server.getConfiguration().getWorkerThreads()));
            } finally {
                meterRegistry.close();
            }
        });
    }
}
