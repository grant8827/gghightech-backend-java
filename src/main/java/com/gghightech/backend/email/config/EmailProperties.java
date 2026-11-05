package com.gghightech.backend.email.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code email.*} settings in application.yml. Secrets come from the environment. */
@ConfigurationProperties("email")
public record EmailProperties(
        String serviceToken,
        String internalToken,
        String keyEncryptionKey,
        String dkimSelector,
        String spfMechanism,
        String smtpHost,
        int smtpPort,
        List<String> dnsResolvers,
        James james) {

    public record James(String webadminUrl, String webadminPassword) {
    }
}
