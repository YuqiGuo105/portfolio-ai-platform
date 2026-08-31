package site.yuqi.mcp.adapter;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Creates a short-lived Google-signed ID token for authenticated Cloud Run calls. */
@Component
public class CloudRunIdentityTokenProvider {

    public String tokenFor(String audience) {
        if (audience == null || audience.isBlank() || !audience.startsWith("https://")) {
            throw new AdapterException("Cloud Run identity-token audience must be a configured HTTPS service URL.");
        }
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            if (!(credentials instanceof IdTokenProvider provider)) {
                throw new AdapterException("Runtime credentials cannot mint a Cloud Run identity token.");
            }
            return IdTokenCredentials.newBuilder()
                    .setIdTokenProvider(provider)
                    .setTargetAudience(audience)
                    .build()
                    .refreshAccessToken()
                    .getTokenValue();
        } catch (IOException e) {
            throw new AdapterException("Unable to mint a Cloud Run identity token.", e);
        }
    }
}
