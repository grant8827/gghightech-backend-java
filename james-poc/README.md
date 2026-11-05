# Apache James proof of concept

Proves that a self-hosted Apache James 3.9.1 can do what the GG HighTech Email
Server needs: dashboard-managed domains and SMTP accounts, per-domain DKIM
signing, sender restriction, delivery events, retries, rate limiting, TLS and
open-relay protection.

**Result (2026-11-05): 24/24 checks passed.** See
`../../GGHIGHTECH_EMAIL_INTEGRATION_AUDIT.md`, sections 10–12.

## What's here

| Path | What it is |
|---|---|
| `mailets/src/` | Custom James mail-processing steps, packaged as a jar in `extensions-jars/` |
| `conf/` | The James config files changed from the 3.9.1 Postgres distribution. Secrets are `${...}` placeholders |
| `poc_tests.py` | The 24 end-to-end checks |
| `fake_internet.py` | Local SMTP server standing in for Gmail and other recipients. `reject*@` gets a 550, `tempfail*@` a 451 |
| `event_receiver.py` | Stands in for the Spring Boot service and logs the delivery events James posts |

## Custom mailets

| Class | Why it's needed |
|---|---|
| `DynamicDkimSign` | James's `DKIMSign` takes one fixed key from config. This picks each domain's key at send time, so a domain added from the dashboard is signed without a restart. It reuses `DKIMSign` for the signing itself. Keys must be PKCS#1 PEM (`BEGIN RSA PRIVATE KEY`) |
| `DeliveryEventNotifier` | Posts `accepted`, `delivered`, `failed` and `rejected` events as JSON. Hooked to `RemoteDelivery`'s `onSuccess` and `bounceProcessor`. Never sends the subject or body |
| `PerAccountRateLimit` | Per-account messages-per-minute cap. Not built into this James distribution |
| `EnforceFromDomain` | A second layer: the `From:` header must be on the account's domain. James's `verifyIdentity` already refuses this at SMTP time |

## Run it again

Needs Java 21, PostgreSQL, and Python with `aiosmtpd`, `dkimpy` and `dnspython`.

1. Download `james-server-postgres-app.zip` 3.9.1 from https://james.apache.org/download.cgi and verify its SHA-512.
2. Copy `conf/*` over the distribution's `conf/`, then fill in the placeholders. Generate a PKCS12 keystore with `keytool`.
3. Compile the mailets against the distribution's `james-server-postgres-app.lib/*`, then `jar` them into `extensions-jars/`.
4. Create a PostgreSQL database and role for James, and set them in `conf/postgres.properties`.
5. Start `fake_internet.py <inbox dir>` and `event_receiver.py <events.jsonl>`, then start James:
   `java -Dworking.directory=. -Dlogback.configurationFile=conf/logback.xml -jar james-server-postgres-app.jar`
6. Write the WebAdmin password to `webadmin.secret`, then run `python poc_tests.py <dir containing webadmin.secret, inbox/, events/, dkim-keys/>`.

The PoC routes all outbound mail to `fake_internet.py` through `RemoteDelivery`'s `gateway`. On the VPS, remove `gateway` and `gatewayPort` so James delivers directly to each recipient's MX.
