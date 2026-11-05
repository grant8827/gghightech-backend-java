"""Full-stack test: drives the Spring Boot management service exactly as the
dashboard will (through its admin API), and checks the effects on a real
Apache James: DNS verification, credentials, DKIM signed with the key the
service holds, delivery history, failures, revocation.

Args: <work dir> with service.env (tokens), dns.json, inbox/.
"""

import email
import json
import pathlib
import smtplib
import ssl
import sys
import time
import urllib.request

import dkim

WORK = pathlib.Path(sys.argv[1])
ENV = dict(line.split("=", 1) for line in (WORK / "service.env").read_text().splitlines() if "=" in line)
API = "http://127.0.0.1:8090/api/v1/email"
DNS_FILE = WORK / "dns.json"
INBOX = WORK / "inbox"

results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail else ""), flush=True)


def api(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"X-Service-Token": ENV["EMAIL_SERVICE_TOKEN"],
                                          "X-Actor-Email": "admin@gghightech.test",
                                          "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def publish(domain_view):
    records = json.loads(DNS_FILE.read_text()) if DNS_FILE.exists() else {}
    for r in domain_view["dnsRecords"]:
        records.setdefault(r["name"], []).append(r["value"])
    DNS_FILE.write_text(json.dumps(records))


def smtp_send(user, password, to, subject):
    s = smtplib.SMTP("127.0.0.1", 5870, timeout=15)
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    s.starttls(context=ctx)
    s.login(user, password)
    msg = (f"From: {user}\r\nTo: {to}\r\nSubject: {subject}\r\nDate: {email.utils.formatdate()}\r\n"
           f"Message-ID: {email.utils.make_msgid(domain=user.split('@')[1])}\r\nMIME-Version: 1.0\r\n"
           f"Content-Type: text/plain\r\n\r\nIntegration test: {subject}\r\n")
    s.sendmail(user, [to], msg)
    s.quit()


def wait_for(fn, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.5)
    return None


def inbox_with(subject):
    for f in sorted(INBOX.glob("*.eml")):
        raw = f.read_bytes()
        if f"Subject: {subject}".encode() in raw:
            return raw
    return None


def dkim_ok(raw):
    records = json.loads(DNS_FILE.read_text())

    def lookup(name, timeout=5):
        values = records.get((name.decode() if isinstance(name, bytes) else name).rstrip(".").lower())
        return values[0].encode() if values else None
    return dkim.verify(raw, dnsfunc=lookup)


def message_status(mail_subject_recipient):
    _, messages = api("GET", "/messages?limit=50")
    return next((m for m in messages if mail_subject_recipient in m["recipients"]), None)


print("== Domain from the dashboard")
status, d = api("POST", "/domains", {"name": "hrbooks.test"})
check("Add domain", status == 201 and d["status"] == "PENDING_DNS", f"HTTP {status}")
domain_id = d["id"]

status, v = api("POST", f"/domains/{domain_id}/verify")
check("Verify before publishing DNS: still pending, 3 problems",
      v["status"] == "PENDING_DNS" and sum(not c["ok"] for c in v["lastCheck"]) == 3)
status, c = api("POST", f"/domains/{domain_id}/accounts", {"localPart": "app"})
check("Credentials refused while unverified", status == 409, c.get("message", "")[:70])

publish(d)
status, v = api("POST", f"/domains/{domain_id}/verify")
check("Verify after publishing DNS (real DNS lookup): VERIFIED", v["status"] == "VERIFIED",
      ", ".join(f"{x['purpose']}={'ok' if x['ok'] else x['problem']}" for x in v["lastCheck"]))

print("\n== SMTP credential and sending")
status, cred = api("POST", f"/domains/{domain_id}/accounts", {"localPart": "app", "description": "HRBooks360"})
check("Create credential", status == 201 and cred["password"], f"{cred.get('username')} @ {cred.get('smtpHost')}:{cred.get('smtpPort')}")
smtp_send(cred["username"], cred["password"], "customer@gmail.test", "it-1 hello")
raw = wait_for(lambda: inbox_with("it-1 hello"))
check("Delivered", raw is not None)
if raw:
    sig = email.message_from_bytes(raw).get("DKIM-Signature", "")
    check("Signed with the service-held key (d=hrbooks.test) and verifies", "d=hrbooks.test" in sig and dkim_ok(raw))
m = wait_for(lambda: (lambda x: x if x and x["status"] == "delivered" else None)(message_status("customer@gmail.test")))
check("Activity shows the message as delivered", m is not None,
      " → ".join(e["event"] for e in m["history"]) if m else "not found")

print("\n== Failure reporting")
smtp_send(cred["username"], cred["password"], "reject-me@gmail.test", "it-2 bounce")
m = wait_for(lambda: (lambda x: x if x and x["status"] == "failed" else None)(message_status("reject-me@gmail.test")))
check("Activity shows the failure with the remote server's reason", m is not None,
      (m["history"][-1]["error"][:80] if m else "not found"))

print("\n== Second domain, no James restart")
status, d2 = api("POST", "/domains", {"name": "shop.test"})
publish(d2)
api("POST", f"/domains/{d2['id']}/verify")
status, cred2 = api("POST", f"/domains/{d2['id']}/accounts", {"localPart": "orders"})
smtp_send(cred2["username"], cred2["password"], "buyer@outlook.test", "it-3 second domain")
raw = wait_for(lambda: inbox_with("it-3 second domain"))
check("Second domain signed with its own key", bool(raw) and "d=shop.test" in
      email.message_from_bytes(raw).get("DKIM-Signature", "") and dkim_ok(raw))

print("\n== Revoke")
status, r = api("POST", f"/accounts/{cred['account']['id']}/revoke")
check("Revoke from the dashboard", status == 200 and r["status"] == "REVOKED")
try:
    smtp_send(cred["username"], cred["password"], "customer@gmail.test", "it-4 after revoke")
    check("Revoked credential can't send", False)
except smtplib.SMTPAuthenticationError as e:
    check("Revoked credential can't send", True, f"SMTP {e.smtp_code}")

status, o = api("GET", "/overview")
check("Overview counts", status == 200 and o["verifiedDomains"] == 2 and o["activeAccounts"] == 1,
      json.dumps({k: o[k] for k in ("domains", "verifiedDomains", "activeAccounts")}) + " " + json.dumps(o["last24h"]))

print(f"\n{sum(results)}/{len(results)} checks passed")
