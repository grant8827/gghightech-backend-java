package com.gghightech.mail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * PoC: keys are read from {keysDirectory}/{domain}.pem (PKCS8). The real
 * service would fetch them from the management service instead.
 *
 * Params: keysDirectory, selector.
 */
public class DynamicDkimSign extends GenericMailet {
    private Path keysDirectory;
    private String selector;
    private final Map<String, DKIMSign> signers = new ConcurrentHashMap<>();

    @Override
    public void init() throws MessagingException {
        keysDirectory = Path.of(getInitParameter("keysDirectory"));
        selector = getInitParameter("selector");
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        Optional<String> domain = SmtpAuth.domain(mail);
        if (domain.isEmpty()) {
            return;
        }
        DKIMSign signer = signers.get(domain.get());
        if (signer == null) {
            signer = load(domain.get());
            if (signer == null) {
                log("No DKIM key for " + domain.get() + "; sending unsigned");
                return;
            }
            signers.put(domain.get(), signer);
        }
        signer.service(mail);
    }

    private DKIMSign load(String domain) throws MessagingException {
        Path keyFile = keysDirectory.resolve(domain + ".pem");
        if (!keyFile.normalize().startsWith(keysDirectory.normalize()) || !Files.isRegularFile(keyFile)) {
            return null;
        }
        String pem;
        try {
            pem = Files.readString(keyFile);
        } catch (IOException e) {
            throw new MessagingException("Could not read DKIM key for " + domain, e);
        }
        String template = "v=1; s=" + selector + "; d=" + domain
                + "; h=from:to:subject:date:message-id:mime-version:content-type; a=rsa-sha256; bh=; b=;";
        Map<String, String> params = Map.of("signatureTemplate", template, "privateKey", pem);
        DKIMSign signer = new DKIMSign(null);
        signer.init(new Config("DKIMSign-" + domain, params, getMailetContext()));
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
