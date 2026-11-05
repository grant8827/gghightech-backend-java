package com.gghightech.mail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

import jakarta.mail.MessagingException;

import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMailet;

/**
 * Posts a delivery event (accepted / delivered / failed / rejected_*) as JSON
 * to the management service. Fire-and-forget: a slow or down endpoint never
 * holds up mail. Never sends the message body or subject.
 *
 * Params: endpoint (URL), event (name), token (optional bearer token).
 */
public class DeliveryEventNotifier extends GenericMailet {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private URI endpoint;
    private String event;
    private String token;

    @Override
    public void init() throws MessagingException {
        endpoint = URI.create(getInitParameter("endpoint"));
        event = getInitParameter("event");
        token = getInitParameter("token", "");
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        String messageId = "";
        try {
            messageId = mail.getMessage().getMessageID();
        } catch (MessagingException ignored) {
            // no Message-ID header: leave blank
        }
        String json = "{"
                + field("event", event) + ","
                + field("mail_id", mail.getName()) + ","
                + field("message_id", messageId) + ","
                + field("auth_user", SmtpAuth.user(mail).orElse("")) + ","
                + field("sender", mail.getMaybeSender().asString("")) + ","
                + "\"recipients\":[" + mail.getRecipients().stream()
                        .map(r -> quote(r.asString())).collect(Collectors.joining(",")) + "],"
                + field("error", mail.getErrorMessage()) + ","
                + field("at", Instant.now().toString())
                + "}";
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        HTTP.sendAsync(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    log("Could not post " + event + " event: " + e.getClass().getSimpleName());
                    return null;
                });
    }

    private static String field(String name, String value) {
        return quote(name) + ":" + quote(value == null ? "" : value);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    @Override
    public String getMailetInfo() {
        return "GG HighTech delivery event notifier";
    }
}
