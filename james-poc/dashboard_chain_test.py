"""Drives the Email Server exactly as the admin dashboard does: through the
FastAPI pass-through (SUPER_ADMIN token), to the Java service, to James.

Args: <work dir> containing admin.token (a SUPER_ADMIN access token for the
FastAPI instance) and dns.json (rewritten to "publish" records). FastAPI is
expected on 127.0.0.1:8011, James submission on 127.0.0.1:5870.
"""

import email.utils
import json
import pathlib
import smtplib
import ssl
import sys
import time
import urllib.request

WORK = pathlib.Path(sys.argv[1])
TOKEN = (WORK / "admin.token").read_text().strip()
API = "http://127.0.0.1:8011/api/v1/email-server"
results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  — {detail}" if detail else ""), flush=True)


def api(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


status, domain = api("POST", "/domains", {"name": "clientsite.test"})
check("Add domain", status == 201 and domain["status"] == "PENDING_DNS", f"HTTP {status}")

status, err = api("POST", f"/domains/{domain['id']}/accounts", {"local_part": "noreply"})
check("Java service's error message reaches the dashboard", status == 409 and "Verify the domain" in err.get("detail", ""),
      err.get("detail", "")[:80])

records = {}
for r in domain["dnsRecords"]:
    records.setdefault(r["name"], []).append(r["value"])
(WORK / "dns.json").write_text(json.dumps(records))
status, verified = api("POST", f"/domains/{domain['id']}/verify")
check("Verify DNS", verified["status"] == "VERIFIED", ", ".join(f"{c['purpose']}={c['ok']}" for c in verified["lastCheck"]))

status, cred = api("POST", f"/domains/{domain['id']}/accounts", {"local_part": "noreply", "description": "Client site"})
check("Create credential (password shown once)", status == 201 and cred["username"] == "noreply@clientsite.test"
      and len(cred["password"]) >= 32, cred.get("username"))

smtp = smtplib.SMTP("127.0.0.1", 5870, timeout=15)
ctx = ssl.create_default_context()
ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
smtp.starttls(context=ctx)
smtp.login(cred["username"], cred["password"])
smtp.sendmail(cred["username"], ["visitor@gmail.test"],
              f"From: {cred['username']}\r\nTo: visitor@gmail.test\r\nSubject: chain\r\nDate: {email.utils.formatdate()}\r\n"
              f"Message-ID: {email.utils.make_msgid(domain='clientsite.test')}\r\n\r\nhello\r\n")
smtp.quit()

message = None
for _ in range(40):
    _, messages = api("GET", "/messages?domain=clientsite.test")
    message = next((m for m in messages if m["status"] == "delivered"), None)
    if message:
        break
    time.sleep(0.5)
check("Email Activity shows it delivered", message is not None,
      " → ".join(h["event"] for h in message["history"]) if message else "")

status, account = api("POST", f"/accounts/{cred['account']['id']}/revoke")
check("Revoke", status == 200 and account["status"] == "REVOKED", f"revokedBy={account.get('revokedBy')}")

status, overview = api("GET", "/overview")
check("Overview", status == 200 and overview["verifiedDomains"] >= 1, json.dumps(overview["last24h"]))

status, _ = api("DELETE", f"/domains/{domain['id']}")
check("Remove domain", status == 204, f"HTTP {status}")

print(f"\n{sum(results)}/{len(results)} checks passed")
