package com.gghightech.backend.email.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sending_domains")
public class SendingDomain {
    public static final String PENDING_DNS = "PENDING_DNS";
    public static final String VERIFIED = "VERIFIED";

    @Id
    private UUID id;
    private String name;
    private String status;
    private String dkimSelector;
    private String dkimPublicKey;
    @Column(name = "dkim_private_key_enc")
    private String dkimPrivateKeyEncrypted;
    private String createdBy;
    private Instant createdAt;
    private Instant verifiedAt;
    private Instant lastCheckedAt;
    private String lastCheckResult;

    protected SendingDomain() {
    }

    public SendingDomain(String name, String dkimSelector, String dkimPublicKey, String dkimPrivateKeyEncrypted,
            String createdBy) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.status = PENDING_DNS;
        this.dkimSelector = dkimSelector;
        this.dkimPublicKey = dkimPublicKey;
        this.dkimPrivateKeyEncrypted = dkimPrivateKeyEncrypted;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void recordCheck(boolean allOk, String resultJson) {
        Instant now = Instant.now();
        this.lastCheckedAt = now;
        this.lastCheckResult = resultJson;
        if (allOk && !VERIFIED.equals(status)) {
            this.verifiedAt = now;
        }
        this.status = allOk ? VERIFIED : PENDING_DNS;
    }

    public boolean isVerified() {
        return VERIFIED.equals(status);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getDkimSelector() {
        return dkimSelector;
    }

    public String getDkimPublicKey() {
        return dkimPublicKey;
    }

    public String getDkimPrivateKeyEncrypted() {
        return dkimPrivateKeyEncrypted;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public String getLastCheckResult() {
        return lastCheckResult;
    }
}
