# GG HighTech Email Server: status, requirements and deployment

Standalone architecture: no Mailgun, SendGrid, SES or any relay. Apache James
delivers directly to each recipient's MX over standard SMTP.

```
Admin dashboard (gghightech.org/admin, SUPER_ADMIN)
   │
FastAPI  (/api/v1/email-server/*, audited)        Railway
   │  private network, X-Service-Token
Java management service (this repo) ── PostgreSQL (Railway)
   │  WebAdmin over TLS, IP-restricted        │  "railway" db: email schema
   │                                          │  "james" db: James's data
   │                                          │      ▲ TLS, TCP proxy
   │                                          ▲ HTTPS, internal token
   ▼                                          │ (DKIM keys, delivery events)
Apache James 3.9.1 (no local database) ───────┘                    VPS
   │  SMTP port 25, direct to MX
recipients' mail servers
```

---

## 1. What is implemented, and how it was verified

"Verified with real James" means run against a real Apache James 3.9.1 and
real PostgreSQL. Only the internet was simulated, by a local SMTP server and a
local DNS server (`james-poc/integration_test.py` 13/13,
`james-poc/dashboard_chain_test.py` 8/8, `james-poc/poc_tests.py` 24/24).

| Feature | State | Verified how |
|---|---|---|
| Add domain | **Implemented end to end.** Saved in PostgreSQL **and** registered in James (WebAdmin `PUT /domains`). If James refuses, nothing is saved | Real James |
| DKIM key per domain | **Implemented.** 2048-bit RSA, generated in Java, AES-GCM encrypted at rest, fetched by James at send time (no restart) | Real James; signatures verified against the published key |
| DNS records (SPF, DKIM, DMARC) | **Implemented.** Exact values plus the Host field to type | Unit tests; dashboard |
| DNS verification | **Implemented.** Live TXT lookups, no cache, precise problems | Local DNS server. *Java against public resolvers not yet exercised* |
| SMTP credential create | **Implemented end to end.** James user created (password hashed by James, shown once, never stored by Java) | Real James: the login sends mail |
| SMTP credential revoke | **Implemented end to end.** James user deleted | Real James: next login gets `535` |
| Sender restriction | **Implemented (James built-in).** An account can send only as its own address, envelope **and** `From:` header | Real James: spoofing refused at SMTP time (`503 5.7.1`) |
| Delivery events | **Implemented end to end.** James mailets post `accepted` / `delivered` / `failed` (with the remote reason) / `rejected_*` / `rate_limited` to Java | Real James → Java → dashboard Activity |
| Retries | **Implemented (James built-in).** Temporary failures queued and retried, then `failed` | Real James (451 test) |
| Open-relay protection, TLS | **Implemented.** AUTH required, only after STARTTLS; no IP-trusted relay | Real James |
| Direct delivery to recipient MX | **Configured, not yet tested.** Removing the PoC `gateway` lines makes `RemoteDelivery` look up MX records and deliver over port 25 | Needs the VPS |
| Dashboard: Overview, Domains & DNS, SMTP Credentials, Email Activity | **Implemented, live** | Production: tab connected to the Java service |

## 2. Partial or not yet built

| Item | State | Needed for |
|---|---|---|
| Rate limiting | **PoC only.** Custom mailet, in memory, applied *after* the message is accepted | Must become an SMTP-time `451` hook with persistent counters |
| Event delivery | **Fire-and-forget.** Events are lost if Java is down | Needs a local outbox on the VPS with retries |
| Asynchronous bounces | **Not built.** Only failures during the SMTP session are reported | Inbound port 25, an MX for a bounce domain, DSN parsing |
| Suppression list | **Not built** | Stop sending to addresses that hard-bounced |
| Extra sending addresses per account (aliases) | **Not built in the UI** (James supports it, see `/address/aliases`) | Apps that send from several addresses |
| Credential rotation | **Not built.** Revoke and create a new one | — |
| Dashboard pages: Applications, Delivery Analytics, Email Testing, Server Settings | **Not built** (4 of the 8 originally listed exist) | — |
| Re-sync Java → James after a James rebuild | **Not built.** Domains can be re-pushed, but SMTP passwords can't, because Java never stores them; all credentials would have to be re-issued | Disaster recovery |
| Production James packaging | **Config only** (`james-poc/conf`, placeholders) | Systemd unit, TLS, firewall (steps below) |

---

## 3. VPS requirements

**Hard requirements. Confirm them in writing with the provider before paying for a year.**

| Requirement | Why |
|---|---|
| **Outbound TCP 25 open** | Delivering to recipients' MX. Most providers block it by default; some unblock on request, some never. **Get written confirmation for your account** |
| **Reverse DNS (PTR) you can set** on the IPv4 address | Gmail and Outlook require forward-confirmed rDNS for every sender |
| **Dedicated static IPv4** | Reputation is per IP. Check it isn't on a blocklist before use (e.g. Spamhaus, MXToolbox blacklist check) |
| Inbound TCP 25, 587 (and 465) allowed | Bounces and authenticated submission |
| IPv6: either disable outbound IPv6 in James, or give it a PTR too | Mail sent over IPv6 without a PTR gets rejected |

**Size:** 2 vCPU, 4 GB RAM (the James JVM uses about 1–2 GB; no database runs on the VPS), 40 GB SSD, **Ubuntu 24.04 LTS**. Hetzner's CX22 fits.

**Providers.** Their policies change, so treat this as a starting point, not a promise:

| Provider | Usual port-25 policy |
|---|---|
| Hetzner Cloud | Blocked for new accounts; unblock request accepted after the account has some history or a paid invoice |
| Vultr | Blocked by default; support ticket explaining the use |
| OVHcloud | Often open on VPS; check the specific range |
| DigitalOcean, AWS Lightsail, most cloud PaaS | Blocked and rarely or never unblocked. Avoid |

---

## 3a. Storage

**Decision: PostgreSQL only, on Railway.** James keeps all its data (domains,
users, mail queue, message bodies) in a dedicated `james` database on the
existing Railway PostgreSQL instance. No database runs on the VPS, and no
object storage is used.

- **Why PostgreSQL for message bodies too** (`blob.properties`:
  `implementation=postgres`): this server only sends. A message is stored only
  until it is delivered or its retries run out, so storage stays at a few
  hundred MB at most. Object storage (e.g. Hetzner Object Storage) only pays
  off for real mailboxes that keep mail long term; James can switch to it
  later through `blob.properties` without other changes.
- **Isolation:** a separate `james` database and `james` role
  (`james-poc/conf-production/railway-james-db.sql`). The role has no
  privileges on the app's `railway` database. Connection pool capped at 10
  (plus 5 for the RLS bypass pool) to share Railway's ~100 connections.
- **Network:** the VPS isn't on Railway's private network, so James connects
  through the Postgres service's **TCP proxy** with `ssl.mode=require`. Turning
  the proxy on makes the database reachable from the internet (password + TLS
  protected; Railway can't IP-allowlist it). Use a long random password for
  every role, including the existing ones, before enabling it.
- **Effect on sending:** none on deliverability. If Railway Postgres is
  unreachable, James stops accepting new mail until it's back; queued mail is
  kept and delivered afterwards. James already depends on Railway at send time
  for DKIM keys, so this adds little new risk.
- **Backups:** covered by Railway's Postgres backups, one place for both
  databases.
- **Later, if email becomes business-critical:** move the `james` database to
  PostgreSQL on the VPS (`pg_dump` / `pg_restore`, then repoint
  `postgres.properties`) so sending keeps working through a Railway outage.

---

## 4. Ports

| Port | Direction | Purpose | Exposure |
|---|---|---|---|
| 25 | **out** | Delivery to recipient MX | Required (provider must allow) |
| 25 | in | Bounces and DSNs (phase 5) | Public |
| 587 | in | SMTP submission, STARTTLS, AUTH required | Public |
| 465 | in | SMTP submission, implicit TLS (optional) | Public |
| 80 | in | Let's Encrypt HTTP-01 certificate challenge | Public (or use DNS-01 and keep it closed) |
| 8443 | in | WebAdmin through nginx TLS | **Only Railway's static outbound IP** |
| 22 | in | SSH, keys only | Your IP only |
| 8000 (WebAdmin), 5432, 9999 (JMX), 143/993/110/995 | — | — | **Closed / bound to 127.0.0.1** |
| 53, 443 | out | DNS, HTTPS to the Java service and Let's Encrypt | Required |

---

## 5. DNS

**Once, for the server** (example hostname `mail.gghightech.org`, IP `203.0.113.10`):

| Record | Host (in eNom) | Value |
|---|---|---|
| A | `mail` | `203.0.113.10` |
| TXT (SPF for the HELO name) | `mail` | `v=spf1 a -all` |
| **PTR** | set at the **VPS provider**, not eNom | `203.0.113.10` → `mail.gghightech.org` |

James's `heloName` must be `mail.gghightech.org`, and the A record and PTR must match each other.

**Per sending domain:** the dashboard generates these, and **Verify DNS** checks them:

| Record | Host | Value |
|---|---|---|
| TXT (SPF) | `@` | `v=spf1 ip4:203.0.113.10 ~all` (or add `ip4:…` to the existing SPF) |
| TXT (DKIM) | `gg1._domainkey` | `v=DKIM1; k=rsa; p=…` |
| TXT (DMARC) | `_dmarc` | `v=DMARC1; p=none; rua=mailto:dmarc@<domain>`, tightened after warm-up |

---

## 6. Deployment steps

1. **Choose a provider and confirm port 25 outbound in writing.** Create the VPS (Ubuntu 24.04). Set its PTR to `mail.gghightech.org`. Check the IP against blocklists.
2. **DNS:** add the A record and the HELO SPF for `mail`.
3. **Harden the server:** SSH keys only, `ufw` with the ports above, `fail2ban`, unattended security upgrades, time sync.
4. **Install** Temurin 21 (no PostgreSQL on the VPS). Then set up James's database on Railway:
   1. Generate a password (`openssl rand -base64 32`), put it in `railway-james-db.sql`, and run it: `railway connect Postgres`, then `\i james-poc/conf-production/railway-james-db.sql`.
   2. Enable the TCP proxy: Railway dashboard → Postgres → Settings → Networking → TCP Proxy. Note the domain and port.
   3. On the VPS, create `/etc/james/james.env` from `conf-production/james.env.example` (chmod 600), with the proxy host, port and password.
   4. Check from the VPS: `psql "host=<proxy> port=<port> dbname=james user=james sslmode=require" -c 'select 1'`.
5. **Install James 3.9.1** (`james-server-postgres-app.zip`, SHA-512 verified) under `/opt/james` as a systemd service running as an unprivileged `james` user. Allow binding to ports 25, 465 and 587 with `CAP_NET_BIND_SERVICE`.
6. **Configure James** from `james-poc/conf/`, with the production storage files from `james-poc/conf-production/`:
   - copy `blob.properties`, and render `postgres.properties` with `envsubst` (command at the top of the file). Don't use the PoC `conf/postgres.properties`, which points at a local test database
   - fill in the other placeholders
   - **remove the `gateway` and `gatewayPort` lines** so James delivers directly to MX
   - set `heloName`
   - enable an inbound port 25 server for bounces only
   - production retry schedule (e.g. 5 retries over about 48 hours)
   - disable outbound IPv6, or set its PTR
   - build `james-poc/mailets` into `extensions-jars/`
7. **TLS:** get a Let's Encrypt certificate for `mail.gghightech.org` with certbot. Point James's TLS config at the PEM files, with a renewal hook that restarts James.
8. **WebAdmin:** bind it to 127.0.0.1:8000 with a strong password. Put nginx TLS on 8443 in front of it, allowlisted to Railway's static outbound IP (Pro plan feature: enable it on `gghightech-backend-java`).
9. **James → Java:** James must reach `/internal/v1/*` on the Java service, which is private on Railway today. Give the Java service a **public Railway domain**. Every endpoint already requires a token, and `/internal` can additionally be IP-restricted to the VPS. Set the James mailet `keysUrl` and `endpoint` to that domain, plus `EMAIL_INTERNAL_TOKEN`.
10. **Java settings on Railway:**
    - `JAMES_WEBADMIN_URL=https://mail.gghightech.org:8443`
    - `JAMES_WEBADMIN_PASSWORD`
    - `EMAIL_SPF_MECHANISM=ip4:<VPS IP>`
    - `EMAIL_SMTP_HOST=mail.gghightech.org`
    - `EMAIL_SMTP_PORT=587`
11. **Acceptance tests, all through the dashboard:**
    1. Add a test domain, publish its records, and verify (public DNS this time).
    2. Create a credential and send with it to **mail-tester.com**. Aim for a score of 10/10.
    3. Send to a Gmail and an Outlook address. Under "Show original", SPF, DKIM and DMARC should all say `PASS`, and the message should land in the inbox.
    4. Send to a nonexistent address and check that a `failed` event with the reason appears in Activity.
    5. Revoke the credential and check that the next login is refused.
12. **Warm-up:** start at low volume (tens per day), increase gradually, and watch Activity and DMARC reports. Move DMARC from `p=none` to `quarantine` once alignment is clean.
