package site.yuqi.mcp.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebClientConfigTest {

    @Test
    void decodesContentLargerThanSpringDefaultBuffer() throws Exception {
        String content = "x".repeat(300_000);
        byte[] response = ("{\"content\":\"" + content + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/content", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Map<?, ?> result = new WebClientConfig()
                    .webClientBuilder(DataSize.ofMegabytes(2))
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build()
                    .get()
                    .uri("/content")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            assertEquals(content.length(), String.valueOf(result.get("content")).length());
        } finally {
            server.stop(0);
        }
    }
}
