package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "socketio.enabled=false")
@Import({MongoTestContainer.class, RedisTestContainer.class,
        VirtualThreadWebIntegrationTest.ThreadProbeConfiguration.class})
class VirtualThreadWebIntegrationTest {

    private static final AtomicBoolean REQUEST_USED_VIRTUAL_THREAD = new AtomicBoolean();

    @Autowired
    private Environment environment;

    @Test
    void servesMvcRequestsOnVirtualThreads() throws IOException, InterruptedException {
        int port = environment.getProperty("local.server.port", Integer.class);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .GET()
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(REQUEST_USED_VIRTUAL_THREAD).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ThreadProbeConfiguration {

        @Bean
        WebMvcConfigurer threadProbe() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public boolean preHandle(
                                jakarta.servlet.http.HttpServletRequest request,
                                jakarta.servlet.http.HttpServletResponse response,
                                Object handler) {
                            REQUEST_USED_VIRTUAL_THREAD.set(Thread.currentThread().isVirtual());
                            return true;
                        }
                    }).addPathPatterns("/api/health");
                }
            };
        }
    }
}
