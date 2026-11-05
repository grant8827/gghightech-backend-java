package com.gghightech.mail;

import java.util.Optional;

import org.apache.mailet.Mail;

/** The SMTP AUTH username James recorded for a mail, if any. */
final class SmtpAuth {
    private SmtpAuth() {
    }

    static Optional<String> user(Mail mail) {
        return mail.getAttribute(Mail.SMTP_AUTH_USER).map(attribute -> attribute.getValue().value().toString());
    }

    static Optional<String> domain(Mail mail) {
        return user(mail).filter(u -> u.contains("@")).map(u -> u.substring(u.indexOf('@') + 1).toLowerCase());
    }
}
