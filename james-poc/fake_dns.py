"""A tiny authoritative DNS server for the integration test: answers TXT
queries from a JSON file ({name: [values]}) that the test rewrites to
"publish" records. Long values are split into 255-byte strings, as real DNS
providers do."""

import json
import socket
import sys

import dns.message
import dns.rcode
import dns.rdataclass
import dns.rdatatype
import dns.rrset

RECORDS_FILE = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 5353


def chunks(value: str):
    raw = value.encode()
    return " ".join('"' + raw[i:i + 255].decode() .replace('"', '\\"') + '"' for i in range(0, len(raw), 255))


sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", PORT))
print(f"fake DNS on 127.0.0.1:{PORT}", flush=True)
while True:
    data, addr = sock.recvfrom(4096)
    try:
        query = dns.message.from_wire(data)
    except Exception:
        continue
    response = dns.message.make_response(query)
    response.flags |= 0x0400  # authoritative
    question = query.question[0]
    name = question.name.to_text().rstrip(".").lower()
    try:
        records = json.load(open(RECORDS_FILE))
    except (OSError, ValueError):
        records = {}
    if question.rdtype == dns.rdatatype.TXT and name in records:
        response.answer.append(dns.rrset.from_text(question.name, 60, dns.rdataclass.IN, dns.rdatatype.TXT,
                                                   *[chunks(v) for v in records[name]]))
    elif not any(k == name or k.endswith("." + name) for k in records):
        response.set_rcode(dns.rcode.NXDOMAIN)
    sock.sendto(response.to_wire(max_size=65535), addr)
