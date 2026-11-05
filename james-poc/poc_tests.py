"""End-to-end proof-of-concept checks against a local Apache James 3.9.1.

Plays three parts: the dashboard/management service (WebAdmin calls, DKIM
key generation), a sending application (SMTP submission), and the observer
(fake-internet inbox + delivery events).
"""

import base64
import email
import json
import pathlib
import smtplib
import ssl
import subprocess
import sys
import time
import urllib.request

import dkim

ROOT = pathlib.Path(sys.argv[1])
WEBADMIN = "http://127.0.0.1:8100"
PASSWORD = (ROOT / "webadmin.secret").read_text().strip()
SMTP_HOST, SMTP_PORT = "127.0.0.1", 5870
INBOX = ROOT / "inbox"
EVENTS = ROOT / "events" / "events.jsonl"
KEYS = ROOT / "dkim-keys"
DNS = {}  # the TXT records we would publish: name -> value

results = []


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail else ""), flush=True)


def webadmin(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(WEBADMIN + path, data=data, method=method, headers={"Password": PASSWORD})
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def add_domain_with_dkim(domain):
    """What the management service does on "Add domain"."""
    status, _ = webadmin("PUT", f"/domains/{domain}")
    pem = subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
                         capture_output=True, check=True).stdout
    # James's DKIMSign reads a PKCS#1 PEM ("BEGIN RSA PRIVATE KEY"), headers included.
    pkcs1 = subprocess.run(["openssl", "rsa"],
                           input=pem, capture_output=True, check=True).stdout.decode()
    pub_der = subprocess.run(["openssl", "pkey", "-pubout", "-outform", "DER"],
                             input=pem, capture_output=True, check=True).stdout
    (KEYS / f"{domain}.pem").write_text(pkcs1)
    DNS[f"gg1._domainkey.{domain}."] = "v=DKIM1; k=rsa; p=" + base64.b64encode(pub_der).decode()
    DNS[f"{domain}."] = "v=spf1 ip4:<VPS_IP> -all"
    DNS[f"_dmarc.{domain}."] = f"v=DMARC1; p=quarantine; rua=mailto:dmarc@{domain}"
    return status


def tls_client():
    s = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15)
    s.ehlo()
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # PoC uses a self-signed certificate
    s.starttls(context=ctx)
    s.ehlo()
    return s


def message(frm, to, subject):
    return (f"From: {frm}\r\nTo: {to}\r\nSubject: {subject}\r\n"
            f"Date: {email.utils.formatdate()}\r\nMessage-ID: {email.utils.make_msgid(domain=frm.split('@')[1])}\r\n"
            f"MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nHello from the PoC: {subject}\r\n")


def send(user, password, mail_from, to, subject, header_from=None):
    s = tls_client()
    s.login(user, password)
    try:
        s.sendmail(mail_from, [to], message(header_from or mail_from, to, subject))
        return "accepted"
    except smtplib.SMTPException as e:
        return f"refused: {e}"
    finally:
        s.quit()


def events():
    if not EVENTS.exists():
        return []
    return [json.loads(line) for line in EVENTS.read_text().splitlines()]


def wait_for(predicate, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        found = predicate()
        if found:
            return found
        time.sleep(0.5)
    return None


def inbox_with(subject):
    for f in sorted(INBOX.glob("*.eml")):
        raw = f.read_bytes()
        if f"Subject: {subject}".encode() in raw:
            return raw
    return None


def dkim_verify(raw):
    def dnsfunc(name, timeout=5):
        return DNS.get(name.decode() if isinstance(name, bytes) else name, "").encode() or None
    return dkim.verify(raw, dnsfunc=dnsfunc)


# ---------------------------------------------------------------- domains
print("\n== Domain management (WebAdmin)")
check("Add domain hrbooks.test + DKIM key", add_domain_with_dkim("hrbooks.test") == 204)
status, domains = webadmin("GET", "/domains")
check("Domain is listed", "hrbooks.test" in (domains or []), str(domains))
check("DNS records generated (SPF, DKIM, DMARC)", len(DNS) == 3, ", ".join(DNS))

# ---------------------------------------------------------------- credentials
print("\n== SMTP credentials")
APP_PW = base64.urlsafe_b64encode(subprocess.run(["openssl", "rand", "24"], capture_output=True).stdout).decode()
status, _ = webadmin("PUT", "/users/app@hrbooks.test", {"password": APP_PW})
check("Create SMTP account app@hrbooks.test", status == 204, f"HTTP {status}")

# ---------------------------------------------------------------- relay / TLS
print("\n== Open-relay protection and TLS")
s = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15)
s.ehlo()
code, resp = s.docmd("MAIL FROM:<anyone@spammer.test>")
if code < 400:
    code, resp = s.docmd("RCPT TO:<victim@gmail.test>")
check("Unauthenticated relay refused", code >= 500, f"{code} {resp.decode()[:70]}")
check("AUTH not offered before STARTTLS", not s.has_extn("auth"), f"EHLO auth={s.esmtp_features.get('auth')}")
s.close()
t = tls_client()
check("STARTTLS works and AUTH is offered after it", t.has_extn("auth"), t.esmtp_features.get("auth", ""))
try:
    t.login("app@hrbooks.test", "wrong-password")
    check("Wrong password refused", False)
except smtplib.SMTPAuthenticationError as e:
    check("Wrong password refused", True, str(e.smtp_code))
t.quit()

# ---------------------------------------------------------------- send + DKIM
print("\n== Authenticated sending and DKIM")
r = send("app@hrbooks.test", APP_PW, "app@hrbooks.test", "customer@gmail.test", "poc-1 signed")
check("Authenticated send accepted", r == "accepted", r)
raw = wait_for(lambda: inbox_with("poc-1 signed"))
check("Delivered to recipient server", raw is not None)
if raw:
    sig = email.message_from_bytes(raw).get("DKIM-Signature", "")
    check("DKIM-Signature present with d=hrbooks.test", "d=hrbooks.test" in sig, sig[:80])
    check("DKIM signature verifies against the published key", dkim_verify(raw))

# ---------------------------------------------------------------- sender restriction
print("\n== Sender restriction")
r = send("app@hrbooks.test", APP_PW, "ceo@othercompany.test", "customer@gmail.test", "poc-2 envelope spoof")
check("Envelope sender on another domain refused", r.startswith("refused"), r[:90])
r = send("app@hrbooks.test", APP_PW, "billing@hrbooks.test", "customer@gmail.test", "poc-3 other local part")
check("Other address on own domain (no alias) refused", r.startswith("refused"), r[:90])
webadmin("PUT", "/address/aliases/app@hrbooks.test/sources/billing@hrbooks.test")
r = send("app@hrbooks.test", APP_PW, "billing@hrbooks.test", "customer@gmail.test", "poc-4 via alias")
check("Same-domain address allowed once added as alias", r == "accepted", r[:90])
r = send("app@hrbooks.test", APP_PW, "app@hrbooks.test", "customer@gmail.test", "poc-5 header spoof",
         header_from="ceo@othercompany.test")
ev = [] if r.startswith("refused") else wait_for(lambda: [e for e in events() if e["event"] == "rejected_sender"])
check("Visible From: header on another domain blocked",
      r.startswith("refused") or (bool(ev) and not inbox_with("poc-5 header spoof")),
      r[:110] if r.startswith("refused") else (ev[0]["error"] if ev else "no event"))

# ---------------------------------------------------------------- second domain, no restart
print("\n== Second domain added at runtime (no restart)")
add_domain_with_dkim("shop.test")
SHOP_PW = "shop-" + APP_PW
webadmin("PUT", "/users/orders@shop.test", {"password": SHOP_PW})
r = send("orders@shop.test", SHOP_PW, "orders@shop.test", "buyer@outlook.test", "poc-6 second domain")
raw = wait_for(lambda: inbox_with("poc-6 second domain"))
check("New domain signed with its own key, no restart",
      bool(raw) and "d=shop.test" in email.message_from_bytes(raw).get("DKIM-Signature", "") and dkim_verify(raw))

# ---------------------------------------------------------------- delivery events
print("\n== Delivery events, failures, retries")
r = send("app@hrbooks.test", APP_PW, "app@hrbooks.test", "reject-me@gmail.test", "poc-7 hard bounce")
ev = wait_for(lambda: [e for e in events() if e["event"] == "failed" and "reject-me@gmail.test" in e["recipients"]])
check("Permanent failure (550) reported as 'failed' with reason", bool(ev), (ev[0]["error"][:90] if ev else "no event"))

r = send("app@hrbooks.test", APP_PW, "app@hrbooks.test", "tempfail@gmail.test", "poc-8 retry")
time.sleep(1.5)
status, queued = webadmin("GET", "/mailQueues/outgoing/mails")
check("Temporary failure (451) waits in the outgoing queue", status == 200 and len(queued or []) >= 1,
      f"{len(queued or [])} queued")
ev = wait_for(lambda: [e for e in events() if e["event"] == "failed" and "tempfail@gmail.test" in e["recipients"]],
              timeout=40)
attempts = sum(1 for line in (ROOT / "fake_internet.log").read_text().splitlines() if "tempfail@gmail.test" in line)
check("Retried, then reported 'failed' after max retries", bool(ev) and attempts >= 3, f"{attempts} attempts")

delivered = [e for e in events() if e["event"] == "delivered"]
accepted = [e for e in events() if e["event"] == "accepted"]
check("'accepted' and 'delivered' events posted", len(accepted) >= 4 and len(delivered) >= 3,
      f"{len(accepted)} accepted, {len(delivered)} delivered")
sample = delivered[0] if delivered else {}
check("Events carry ids/addresses but no subject or body",
      bool(sample) and "subject" not in sample and "body" not in sample, json.dumps(sample)[:160])

# ---------------------------------------------------------------- rate limit
print("\n== Rate limiting")
webadmin("PUT", "/users/burst@shop.test", {"password": SHOP_PW})
for i in range(6):
    send("burst@shop.test", SHOP_PW, "burst@shop.test", "list@outlook.test", f"poc-9 burst {i}")
ev = wait_for(lambda: [e for e in events() if e["event"] == "rate_limited"])
check("6th message in a minute blocked (limit 5/min)", bool(ev), ev[0]["error"] if ev else "no event")

# ---------------------------------------------------------------- revoke
print("\n== Revoking credentials")
status, _ = webadmin("DELETE", "/users/app@hrbooks.test")
try:
    t = tls_client()
    t.login("app@hrbooks.test", APP_PW)
    check("Revoked account can no longer log in", False, f"delete HTTP {status}")
except smtplib.SMTPAuthenticationError as e:
    check("Revoked account can no longer log in", True, f"delete HTTP {status}, login {e.smtp_code}")

print(f"\n{sum(ok for _, ok in results)}/{len(results)} checks passed")
print("\nDNS records the dashboard would show for hrbooks.test:")
for name, value in DNS.items():
    if "hrbooks" in name:
        print(f"  TXT {name}  {value[:70]}{'…' if len(value) > 70 else ''}")
