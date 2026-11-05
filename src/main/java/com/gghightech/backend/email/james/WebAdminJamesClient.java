package com.gghightech.backend.email.james;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.gghightech.backend.email.config.EmailProperties;

/**
 * James WebAdmin over HTTP, authenticated with the static WebAdmin password
 * in the "Password" header. WebAdmin must only be reachable over the private
 * tunnel to the mail server, never the public internet.
 */
@Component
public class WebAdminJamesClient implements JamesAdmin {
    private final RestClient http;
    private final boolean configured;

    public WebAdminJamesClient(EmailProperties properties) {
        String password = properties.james().webadminPassword();
        this.configured = password != null && !password.isBlank();
        this.http = RestClient.builder()
                .baseUrl(properties.james().webadminUrl())
                .defaultHeader("Password", configured ? password : "")
                .build();
    }

    @Override
    public void addDomain(String domain) {
        call(() -> http.put().uri("/domains/{d}", domain).retrieve().toBodilessEntity());
    }

    @Override
    public void removeDomain(String domain) {
        callAllowingNotFound(() -> http.delete().uri("/domains/{d}", domain).retrieve().toBodilessEntity());
    }

    @Override
    public void createUser(String address, String password) {
        call(() -> http.put().uri("/users/{u}", address).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("password", password)).retrieve().toBodilessEntity());
    }

    @Override
    public void deleteUser(String address) {
        callAllowingNotFound(() -> http.delete().uri("/users/{u}", address).retrieve().toBodilessEntity());
    }

    private void callAllowingNotFound(Runnable request) {
        try {
            call(request);
        } catch (ResponseStatusException e) {
            if (!(e.getCause() instanceof RestClientResponseException r && r.getStatusCode().value() == 404)) {
                throw e;
            }
        }
    }

    private void call(Runnable request) {
        if (!configured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The mail server is not configured");
        }
        try {
            request.run();
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The mail server refused the request (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach the mail server", e);
        }
    }
}
