package com.gghightech.backend.email.event;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_events")
public class DeliveryEvent {
    @Id
    private UUID id;
    private String mailId;
    private String messageId;
    private String event;
    private String authUser;
    private String domain;
    private String sender;
    private String recipients;
    private String error;
    private Instant occurredAt;
    private Instant receivedAt;

    protected DeliveryEvent() {
    }

    public DeliveryEvent(String mailId, String messageId, String event, String authUser, String domain, String sender,
            String recipients, String error, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.mailId = mailId;
        this.messageId = messageId;
        this.event = event;
        this.authUser = authUser;
        this.domain = domain;
        this.sender = sender;
        this.recipients = recipients;
        this.error = error;
        this.occurredAt = occurredAt;
        this.receivedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getMailId() {
        return mailId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getEvent() {
        return event;
    }

    public String getAuthUser() {
        return authUser;
    }

    public String getDomain() {
        return domain;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipients() {
        return recipients;
    }

    public String getError() {
        return error;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
