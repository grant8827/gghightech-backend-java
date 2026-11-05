"""Stands in for recipient mail servers (Gmail etc.) in the PoC.

Saves each delivered message to inbox/. Recipients whose local part starts
with "reject" get a permanent 550; "tempfail" gets a temporary 451 (so
retries can be observed).
"""

import asyncio
import pathlib
import sys
import time

from aiosmtpd.controller import Controller

INBOX = pathlib.Path(sys.argv[1])
INBOX.mkdir(parents=True, exist_ok=True)


class Handler:
    async def handle_RCPT(self, server, session, envelope, address, rcpt_options):
        local = address.split("@")[0].lower()
        if local.startswith("reject"):
            print(f"{time.strftime('%X')} RCPT {address} -> 550", flush=True)
            return "550 5.1.1 No such user here"
        if local.startswith("tempfail"):
            print(f"{time.strftime('%X')} RCPT {address} -> 451", flush=True)
            return "451 4.3.0 Try again later"
        envelope.rcpt_tos.append(address)
        return "250 OK"

    async def handle_DATA(self, server, session, envelope):
        name = f"{time.time_ns()}.eml"
        (INBOX / name).write_bytes(envelope.original_content or envelope.content)
        print(f"{time.strftime('%X')} DELIVERED from={envelope.mail_from} to={envelope.rcpt_tos} file={name}", flush=True)
        return "250 Message accepted"


controller = Controller(Handler(), hostname="127.0.0.1", port=2526)
controller.start()
print("fake internet SMTP listening on 127.0.0.1:2526", flush=True)
try:
    asyncio.get_event_loop().run_forever()
finally:
    controller.stop()
