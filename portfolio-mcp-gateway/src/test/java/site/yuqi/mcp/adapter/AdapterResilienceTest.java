package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.model.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AdapterResilienceTest {
    @Test void writeIsNeverRetriedAndCircuitStopsRepeatedFailures() {
        var requests=new AtomicInteger();
        var builder=WebClient.builder().exchangeFunction(r -> {
            requests.incrementAndGet(); return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("private error").build());
        });
        var adapter=new AbstractHttpAdapter(builder) {
            public String target() { return "test"; }
            protected String baseUrl() { return "http://test.invalid"; }
        };
        var tool=ToolDefinition.builder().name("write").mode(ToolMode.WRITE)
                .endpoint(new ToolDefinition.Endpoint("test","POST","/write")).build();
        for(int i=0;i<5;i++) assertThatThrownBy(() -> adapter.invoke(tool,Map.of())).isInstanceOf(AdapterException.class);
        assertThat(requests.get()).isEqualTo(5);
        try { adapter.invoke(tool,Map.of()); fail("Expected circuit open"); }
        catch(AdapterException e) { assertThat(e.isNotDispatched()).isTrue(); }
        assertThat(requests.get()).isEqualTo(5);
    }
}
