-- Email Server management data. Lives in its own "email" schema, migrated by
-- Flyway from this service. The FastAPI backend's Alembic migrations own the
-- "public" schema and never look here.

CREATE TABLE sending_domains (
    id                      UUID PRIMARY KEY,
    name                    VARCHAR(253) NOT NULL UNIQUE,
    -- PENDING_DNS until SPF, DKIM and DMARC are all found in public DNS.
    status                  VARCHAR(20)  NOT NULL,
    dkim_selector           VARCHAR(63)  NOT NULL,
    -- base64 DER SubjectPublicKeyInfo: the p= value of the DKIM TXT record.
    dkim_public_key         TEXT         NOT NULL,
    -- PKCS#1 PEM, AES-256-GCM encrypted with EMAIL_KEY_ENCRYPTION_KEY.
    dkim_private_key_enc    TEXT         NOT NULL,
    created_by              VARCHAR(255),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at             TIMESTAMP WITH TIME ZONE,
    last_checked_at         TIMESTAMP WITH TIME ZONE,
    -- JSON: per-record result of the last DNS check.
    last_check_result       TEXT,
    CONSTRAINT ck_sending_domains_status CHECK (status IN ('PENDING_DNS', 'VERIFIED'))
);

CREATE TABLE smtp_accounts (
    id              UUID PRIMARY KEY,
    domain_id       UUID         NOT NULL REFERENCES sending_domains (id) ON DELETE CASCADE,
    -- The SMTP username, and the only address it may send as.
    address         VARCHAR(320) NOT NULL UNIQUE,
    description     VARCHAR(255),
    status          VARCHAR(20)  NOT NULL,
    created_by      VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at      TIMESTAMP WITH TIME ZONE,
    revoked_by      VARCHAR(255),
    CONSTRAINT ck_smtp_accounts_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX ix_smtp_accounts_domain ON smtp_accounts (domain_id);

-- One row per event James posts (accepted, delivered, failed, rejected_*,
-- rate_limited, error). No subject or body is ever stored.
CREATE TABLE delivery_events (
    id              UUID PRIMARY KEY,
    mail_id         VARCHAR(255) NOT NULL,
    message_id      VARCHAR(998),
    event           VARCHAR(40)  NOT NULL,
    auth_user       VARCHAR(320),
    domain          VARCHAR(253),
    sender          VARCHAR(320),
    -- Comma-separated recipient addresses.
    recipients      TEXT,
    error           TEXT,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_delivery_events_occurred ON delivery_events (occurred_at);
CREATE INDEX ix_delivery_events_mail ON delivery_events (mail_id);
CREATE INDEX ix_delivery_events_domain ON delivery_events (domain, occurred_at);
