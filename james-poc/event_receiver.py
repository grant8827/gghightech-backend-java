"""Stands in for the Spring Boot management service: records the delivery
events James posts, one JSON object per line."""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = sys.argv[1]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        event = json.loads(body)
        with open(LOG, "a") as f:
            f.write(json.dumps(event) + "\n")
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", 8199), Handler).serve_forever()
