package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudRunIdentityTokenProviderTest {
    private final CloudRunIdentityTokenProvider provider = new CloudRunIdentityTokenProvider();

    @Test
    void rejectsMissingAudienceBeforeCallingTheMetadataServer() {
        assertThrows(AdapterException.class, () -> provider.tokenFor(""));
    }

    @Test
    void rejectsNonHttpsAudienceBeforeCallingTheMetadataServer() {
        assertThrows(AdapterException.class, () -> provider.tokenFor("http://career.internal"));
    }
}
