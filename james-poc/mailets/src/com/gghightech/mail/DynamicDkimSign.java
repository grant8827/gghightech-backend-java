package com.gghightech.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.mail.MessagingException;

import org.apache.james.jdkim.mailets.DKIMSign;
import org.apache.mailet.Mail;
import org.apache.mailet.MailetConfig;
import org.apache.mailet.MailetContext;
import org.apache.mailet.base.GenericMailet;

/**
 * DKIM-signs each message with its own domain's key, using James's stock
 * DKIMSign for the signing itself. Keys are looked up per message, so a
 * domain added from the dashboard is signed without restarting James.
 *
 * Key source, one of:
 *  - keysUrl (+ token): GET {keysUrl}/{domain} on the management service,
 *    which returns the PKCS#1 PEM. Cached for cacheSeconds.
 *  - keysDirectory: {keysDirectory}/{domain}.pem (PKCS#1 PEM). PoC only.
 *
 * If no key is available the message goes out unsigned (and is logged);
 * the management service only issues SMTP accounts for verified domains,
 * which always have a key.
 *
 * Params: selector, keysUrl | keysDirectory, token, cacheSeconds (default 300).
 */
public class DynamicDkimSign extends GenericMailet {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private record Cached(DKIMSign signer, long expiresAt) {
    }

    private String selector;
    private String keysUrl;
    private String token;
    private Path keysDirectory;
    private long cacheMillis;
    private final Map<String, Cached> signers = new ConcurrentHashMap<>();

    @Override
    public void init() throws MessagingException {
        selector = getInitParameter("selector");
        keysUrl = getInitParameter("keysUrl");
        token = getInitParameter("token", "");
        String dir = getInitParameter("keysDirectory");
        keysDirectory = dir == null ? null : Path.of(dir);
        cacheMillis = Long.parseLong(getInitParameter("cacheSeconds", "300")) * 1000;
        if (keysUrl == null && keysDirectory == null) {
            throw new MessagingException("DynamicDkimSign needs keysUrl or keysDirectory");
        }
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        Optional<String> domain = SmtpAuth.domain(mail);
        if (domain.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Cached cached = signers.get(domain.get());
        if (cached == null || cached.expiresAt() < now) {
            Optional<String> pem = loadPem(domain.get());
            if (pem.isEmpty()) {
                log("No DKIM key for " + domain.get() + "; sending unsigned");
                return;
            }
            cached = new Cached(signer(domain.get(), pem.get()), now + cacheMillis);
            signers.put(domain.get(), cached);
        }
        cached.signer().service(mail);
    }

    private Optional<String> loadPem(String domain) throws MessagingException {
        if (keysUrl != null) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(keysUrl + "/" + domain))
                    .timeout(Duration.ofSeconds(5)).GET();
            if (!token.isEmpty()) {
                request.header("Authorization", "Bearer " + token);
            }
            try {
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    return Optional.empty();
                }
                if (response.statusCode() != 200) {
                    // Fail the message into the error processor rather than sending it unsigned.
                    throw new MessagingException("DKIM key service returned HTTP " + response.statusCode());
                }
                return Optional.of(response.body());
            } catch (IOException e) {
                throw new MessagingException("Could not reach the DKIM key service", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted fetching DKIM key", e);
            }
        }
        Path keyFile = keysDirectory.resolve(domain + ".pem");
        if (!keyFile.normalize().startsWith(keysDirectory.normalize()) || !Files.isRegularFile(keyFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(keyFile));
        } catch (IOException e) {
            throw new MessagingException("Could not read DKIM key for " + domain, e);
        }
    }

    private DKIMSign signer(String domain, String pem) throws MessagingException {
        String template = "v=1; s=" + selector + "; d=" + domain
                + "; h=from:to:subject:date:message-id:mime-version:content-type; a=rsa-sha256; bh=; b=;";
        DKIMSign signer = new DKIMSign(null);
        signer.init(new Config("DKIMSign-" + domain, Map.of("signatureTemplate", template, "privateKey", pem),
                getMailetContext()));
        return signer;
    }

    private record Config(String name, Map<String, String> params, MailetContext context) implements MailetConfig {
        @Override
        public String getInitParameter(String key) {
            return params.get(key);
        }

        @Override
        public Iterator<String> getInitParameterNames() {
            return Collections.unmodifiableSet(params.keySet()).iterator();
        }

        @Override
        public MailetContext getMailetContext() {
            return context;
        }

        @Override
        public String getMailetName() {
            return name;
        }
    }

    @Override
    public String getMailetInfo() {
        return "GG HighTech per-domain DKIM signing";
    }
}
