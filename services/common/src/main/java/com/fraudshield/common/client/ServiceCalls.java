package com.fraudshield.common.client;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Calls between FraudShield services. Two ways to authenticate:
 * <ul>
 *   <li>{@link #asService()}: the calling service's own identity. In Azure this is its workload identity, which
 *       must hold the DECISION_ENGINE app role on fraudshield-api.</li>
 *   <li>{@link #asCaller()}: forwards the signed-in person's token, so the called service applies that person's
 *       permissions. Copilot uses this, so it can never read more than the analyst can.</li>
 * </ul>
 */
@Component
public class ServiceCalls {

    private final String serviceScope;
    private final String serviceName;
    private final boolean local;
    private TokenCredential credential;
    private AccessToken token;

    public ServiceCalls(@Value("${fraudshield.auth.service-scope:}") String serviceScope,
                        @Value("${spring.application.name}") String serviceName,
                        Environment environment) {
        this.serviceScope = serviceScope;
        this.serviceName = serviceName;
        this.local = environment.acceptsProfiles(Profiles.of("local", "test"));
    }

    /** A client with short timeouts. Callers must handle failure, because the payment path cannot wait. */
    public RestClient client(String baseUrl, Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Consumer<HttpHeaders> asService() {
        if (local) {
            return h -> {
                h.set("X-Dev-User", serviceName + "@fraudshield.internal");
                h.set("X-Dev-Roles", "DECISION_ENGINE");
            };
        }
        if (serviceScope.isBlank()) {
            throw new IllegalStateException("fraudshield.auth.service-scope (FRAUDSHIELD_SERVICE_SCOPE) is required");
        }
        String bearer = serviceToken();
        return h -> h.setBearerAuth(bearer);
    }

    public Consumer<HttpHeaders> asCaller() {
        var attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new IllegalStateException("asCaller() needs an incoming request");
        }
        HttpServletRequest request = attributes.getRequest();
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String devUser = request.getHeader("X-Dev-User");
        String devRoles = request.getHeader("X-Dev-Roles");
        return h -> {
            if (authorization != null) h.set(HttpHeaders.AUTHORIZATION, authorization);
            if (local && devUser != null) {
                h.set("X-Dev-User", devUser);
                h.set("X-Dev-Roles", devRoles == null ? "" : devRoles);
            }
        };
    }

    /** Reuses the token until five minutes before it expires. */
    private synchronized String serviceToken() {
        if (token == null || token.getExpiresAt().isBefore(OffsetDateTime.now().plusMinutes(5))) {
            token = credential().getTokenSync(new TokenRequestContext().addScopes(serviceScope));
        }
        return token.getToken();
    }

    private synchronized TokenCredential credential() {
        if (credential == null) {
            credential = new DefaultAzureCredentialBuilder().build();
        }
        return credential;
    }
}