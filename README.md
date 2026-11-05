# GG HighTech — Email Server management service

A Spring Boot 4 service (Java 17, Maven) that manages the self-hosted Apache
James mail server for every domain you send from: sending domains,
SPF/DKIM/DMARC records and DNS verification, SMTP credentials, and the
delivery log.

**Not deployed yet.** The production API is still the FastAPI app in
`../backend-fastapi`, and the site's own email stays on Mailgun. Plan,
limitations and test results:
`../GGHIGHTECH_EMAIL_INTEGRATION_AUDIT.md`.

```
Admin dashboard ──► FastAPI (/api/v1/email-server/*, SUPER_ADMIN only)
                         │  X-Service-Token + X-Actor-Email
                         ▼
               this service (/api/v1/email/*) ──► PostgreSQL, schema "email"
                         │  WebAdmin (private tunnel)        ▲
                         ▼                                   │ /internal/v1/*
                    Apache James (VPS) ─────────────────────┘ events, DKIM keys
                         │  SMTP, port 25
                         ▼
                  recipients' mail servers
```

## Run

```bash
./mvnw test        # in-memory H2; James and DNS are faked
./mvnw spring-boot:run
```

`james-poc/` has the James configuration, the custom James mailets, and two
end-to-end harnesses: `poc_tests.py` for James alone, and
`integration_test.py` for this service plus James. See `james-poc/README.md`.

## Configuration

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL. Use a dedicated role that owns only the `email` schema |
| `EMAIL_SERVICE_TOKEN` | Shared with the FastAPI proxy. Required on `/api/v1/email/*` |
| `EMAIL_INTERNAL_TOKEN` | Shared with James's mailets. Required on `/internal/v1/*` |
| `EMAIL_KEY_ENCRYPTION_KEY` | base64, 32 bytes (`openssl rand -base64 32`). Encrypts DKIM private keys at rest. **Losing it means every domain needs a new DKIM key** |
| `EMAIL_SPF_MECHANISM` | What each domain's SPF must include, e.g. `ip4:<VPS IP>` |
| `EMAIL_SMTP_HOST` / `EMAIL_SMTP_PORT` | Shown with each new credential, e.g. `mail.gghightech.org` / `587` |
| `EMAIL_DKIM_SELECTOR` | Default `gg1` |
| `EMAIL_DNS_RESOLVERS` | Default `1.1.1.1,8.8.8.8`. `host:port` is allowed |
| `JAMES_WEBADMIN_URL` / `JAMES_WEBADMIN_PASSWORD` | James WebAdmin, over the private tunnel only |
| `PORT` | Default `8080` |

A blank token rejects every request to the area it protects.

## API

All admin calls need `X-Service-Token`. `X-Actor-Email` is recorded as
`created_by` and `revoked_by`.

| Method & path | Does |
|---|---|
| `GET /api/v1/email/overview` | Domain and account counts, last-24h event counts, SMTP host |
| `GET /api/v1/email/domains` | All domains with their required DNS records and last check |
| `POST /api/v1/email/domains` `{name}` | Generates a DKIM key, registers the domain in James, returns the records to publish |
| `POST /api/v1/email/domains/{id}/verify` | Looks the records up in public DNS now; `VERIFIED` when SPF, DKIM and DMARC are all correct |
| `DELETE /api/v1/email/domains/{id}` | Revokes its accounts and removes it from James |
| `GET /api/v1/email/domains/{id}/accounts` | SMTP accounts (never passwords) |
| `POST /api/v1/email/domains/{id}/accounts` `{localPart, description}` | Verified domains only. Returns the password **once** |
| `POST /api/v1/email/accounts/{id}/revoke` | Removes the login from James; takes effect on the next connection |
| `GET /api/v1/email/messages?domain=&limit=` | Recent messages with status and the history of each |
| `GET /api/v1/health`, `GET /actuator/health` | Health |

The mail server calls (`Authorization: Bearer <internal token>`):

| Method & path | Does |
|---|---|
| `POST /internal/v1/events` | Records a delivery event (no subject or body is ever sent) |
| `GET /internal/v1/dkim/{domain}` | The decrypted DKIM key (PKCS#1 PEM) for signing |

## Database

Flyway migrations in `src/main/resources/db/migration` own the `email`
schema. FastAPI's Alembic owns `public`. Hibernate never alters tables
(`ddl-auto: none`).
