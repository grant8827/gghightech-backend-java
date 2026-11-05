package com.gghightech.backend.email.account;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Metadata only. The password exists in James (hashed) and is shown to the admin once. */
@Entity
@Table(name = "smtp_accounts")
public class SmtpAccount {
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    @Id
    private UUID id;
    private UUID domainId;
    private String address;
    private String description;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant revokedAt;
    private String revokedBy;

    protected SmtpAccount() {
    }

    public SmtpAccount(UUID domainId, String address, String description, String createdBy) {
        this.id = UUID.randomUUID();
        this.domainId = domainId;
        this.address = address;
        this.description = description;
        this.status = ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void revoke(String actor) {
        this.status = REVOKED;
        this.revokedAt = Instant.now();
        this.revokedBy = actor;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDomainId() {
        return domainId;
    }

    public String getAddress() {
        return address;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }
}
