package com.gghightech.mail;

import java.util.Optional;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;

import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMailet;

/**
 * James's verifyIdentity only checks the envelope sender (MAIL FROM). This
 * also requires every address in the visible From: header to be on the
 * SMTP account's own domain, so a credential for one domain can't send
 * mail that appears to come from another (and DKIM/DMARC stay aligned).
 *
 * Params: deniedProcessor.
 */
public class EnforceFromDomain extends GenericMailet {
    private String deniedProcessor;

    @Override
    public void init() throws MessagingException {
        deniedProcessor = getInitParameter("deniedProcessor");
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        Optional<String> allowed = SmtpAuth.domain(mail);
        Address[] from = mail.getMessage().getFrom();
        String problem = null;
        if (allowed.isEmpty()) {
            problem = "Not sent by an authenticated SMTP account";
        } else if (from == null || from.length == 0) {
            problem = "Missing From header";
        } else {
            for (Address address : from) {
                String email = address instanceof InternetAddress ia ? ia.getAddress() : address.toString();
                String domain = email.contains("@") ? email.substring(email.indexOf('@') + 1).toLowerCase() : "";
                if (!domain.equals(allowed.get())) {
                    problem = "From header domain " + domain + " is not allowed for this SMTP account";
                    break;
                }
            }
        }
        if (problem != null) {
            mail.setErrorMessage(problem);
            mail.setState(deniedProcessor);
        }
    }

    @Override
    public String getMailetInfo() {
        return "GG HighTech From-header domain enforcement";
    }
}
