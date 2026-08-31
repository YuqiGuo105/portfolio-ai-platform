package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CareerServiceAdapterTest {
    @Test
    void forwardsInternalTokenUsingDomainContractHeader() {
        CloudRunIdentityTokenProvider identityTokenProvider = mock(CloudRunIdentityTokenProvider.class);
        CareerServiceAdapter adapter = new CareerServiceAdapter(WebClient.builder(), identityTokenProvider);
        ReflectionTestUtils.setField(adapter, "internalToken", "career-token");
        WebClient.RequestHeadersSpec<?> request = mock(WebClient.RequestHeadersSpec.class);
        adapter.decorate(request, Map.of());
        verify(request).header("X-Internal-Token", "career-token");
        verify(identityTokenProvider, never()).tokenFor(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void addsCloudRunIdentityTokenWhenEnabled() {
        CloudRunIdentityTokenProvider identityTokenProvider = mock(CloudRunIdentityTokenProvider.class);
        CareerServiceAdapter adapter = new CareerServiceAdapter(WebClient.builder(), identityTokenProvider);
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://career.example.run.app");
        ReflectionTestUtils.setField(adapter, "internalToken", "career-token");
        ReflectionTestUtils.setField(adapter, "cloudRunIdTokenEnabled", true);
        when(identityTokenProvider.tokenFor("https://career.example.run.app")).thenReturn("google-id-token");
        WebClient.RequestHeadersSpec<?> request = mock(WebClient.RequestHeadersSpec.class);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.http.HttpHeaders> consumer = invocation.getArgument(0);
            consumer.accept(headers);
            return request;
        }).when(request).headers(org.mockito.ArgumentMatchers.any());

        adapter.decorate(request, Map.of());

        verify(request).header("X-Internal-Token", "career-token");
        org.junit.jupiter.api.Assertions.assertEquals("Bearer google-id-token", headers.getFirst("Authorization"));
    }
}
