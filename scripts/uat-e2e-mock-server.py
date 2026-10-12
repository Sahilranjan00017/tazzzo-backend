#!/usr/bin/env python3
"""A tiny in-memory stand-in for the Tazzzo customer API, used ONLY to test scripts/uat-e2e-cod.sh offline.

It speaks the subset of docs/api/v1/openapi.yaml the E2E script uses (health, serviceability, catalogue, OTP login, profile,
addresses, cart, checkout quote, COD orders, refresh, logout) with plausible semantics, and can be told to misbehave in one
specific way (--scenario) so the script's failure detection can be tested. It is not the backend and proves nothing about it.

Usage: uat-e2e-mock-server.py --port-file FILE [--secrets-file FILE] [--scenario NAME] [--otp 482915]
It binds to 127.0.0.1 on a free port, writes the port to --port-file, and writes every token / grant / otp it issues to
--secrets-file (so the self-test can assert that none of them is ever printed by the script).
Scenarios: happy (default), capped-stock, stock-not-moved, stock-double-move, refresh-reuse-ok, logout-not-revoking,
           cart-not-cleared, order-500, not-serviceable, anonymous-open, no-products, replay-new-order
"""
import argparse
import json
import re
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ARGS = None
LOCK = threading.Lock()
PRICE = 5000
STATE = {}
REQUESTS = [0]   # every request except /__stats


def reset():
    STATE.clear()
    STATE.update(
        stock=5,
        sessions={},        # sessionId -> {"active": bool, "refresh": str, "access": set()}
        challenges={},      # challengeId -> phone
        grants={},          # grantId -> True
        profile_version=0,
        display_name=None,
        addresses={},       # id -> dict
        addr_seq=0,
        cart={"version": 0, "items": {}},
        quotes={},          # idempotency key -> quote
        quotes_by_id={},
        orders={},          # id -> order
        order_by_quote={},
        order_seq=0,
        secrets=[],
    )


def issue(prefix):
    v = prefix + secrets.token_urlsafe(24)
    STATE["secrets"].append(v)
    if ARGS.secrets_file:
        with open(ARGS.secrets_file, "a") as f:
            f.write(v + "\n")
    return v


def err(status, code, message="error"):
    return status, {"code": code, "message": message, "requestId": "req-mock"}, {}


def product_json():
    stock = STATE["stock"]
    scen = ARGS.scenario
    if scen == "capped-stock":
        state, low, mx = "IN_STOCK", None, 20
    elif stock <= 0:
        state, low, mx = "OUT_OF_STOCK", None, 0
    else:
        state, low, mx = "LOW_STOCK", stock, min(stock, 20)
    return {
        "skuId": "TZP-1001", "productId": "TZP-1001", "name": "Mock Rice 1kg", "stockState": state,
        "lowStockRemaining": low, "maxOrderQuantity": mx, "minimumOrderQuantity": 1,
        "sellingPricePaise": PRICE, "mrpPaise": 6000, "buyable": state != "OUT_OF_STOCK", "serviceable": True,
        "requestId": "req-mock",
    }


def auth_session(headers):
    h = headers.get("Authorization", "")
    if not h.startswith("Bearer "):
        return None
    tok = h[7:]
    for sid, s in STATE["sessions"].items():
        if s["active"] and tok in s["access"]:
            return sid
    return None


def cart_json():
    c = STATE["cart"]
    items = [{"skuId": k, "quantity": q, "addedAt": "2026-10-12T00:00:00Z", "updatedAt": "2026-10-12T00:00:00Z",
              "buyable": True, "issues": []} for k, q in c["items"].items()]
    return {"version": c["version"], "items": items, "itemCount": sum(c["items"].values()),
            "distinctItemCount": len(items), "subtotalPaise": PRICE * sum(c["items"].values()),
            "requestId": "req-mock", "freshness": "FRESH"}


def order_json(o):
    return {"orderId": o["id"], "status": "CONFIRMED", "paymentMethod": "COD", "paymentCondition": "COD_DUE",
            "items": [{"skuId": k, "title": "Mock Rice 1kg", "brandCode": "MOCK", "quantity": q, "unitPricePaise": PRICE,
                       "lineTotalPaise": PRICE * q} for k, q in o["items"].items()],
            "itemCount": sum(o["items"].values()), "subtotalPaise": PRICE * sum(o["items"].values()), "currency": "INR",
            "money": {"merchandiseSubtotalPaise": PRICE * sum(o["items"].values()), "benefitDiscountPaise": 0,
                      "payablePaise": PRICE * sum(o["items"].values())},
            "createdAt": "2026-10-12T00:00:00Z", "confirmedAt": "2026-10-12T00:00:00Z", "requestId": "req-mock"}


def if_match(headers, expected):
    got = headers.get("If-Match")
    if got is None:
        return err(428, "PRECONDITION_REQUIRED")
    if got != '"%s"' % expected:
        return err(412, "PRECONDITION_FAILED")
    return None


def dispatch(method, path, query, headers, body):
    scen = ARGS.scenario
    if path == "/__stats":
        return 200, {"requests": REQUESTS[0]}, {}
    REQUESTS[0] += 1
    if method == "GET" and path in ("/health/live", "/health/ready"):
        return 200, {"status": "UP"}, {}
    if method == "GET" and path == "/v1/serviceability":
        ok = scen != "not-serviceable"
        return 200, {"serviceable": ok, "serviceAreaId": "AREA-1" if ok else None, "requestId": "req-mock"}, {}
    if method == "GET" and path == "/v1/categories":
        return 200, {"resolvedReleaseId": "r1", "items": [{"id": "TZV-000001", "name": "Grocery"}], "requestId": "r"}, {}
    m = re.fullmatch(r"/v1/categories/([^/]+)/children", path)
    if method == "GET" and m:
        items = [{"id": "TZC-000002", "name": "Rice"}] if m.group(1) == "TZV-000001" else []
        return 200, {"resolvedReleaseId": "r1", "items": items, "requestId": "r"}, {}
    m = re.fullmatch(r"/v1/categories/([^/]+)/products", path)
    if method == "GET" and m:
        items = [product_json()] if (m.group(1) == "TZC-000002" and scen != "no-products") else []
        return 200, {"resolvedReleaseId": "r1", "items": items, "hasMore": False, "requestId": "r"}, {}
    m = re.fullmatch(r"/v1/products/([^/]+)", path)
    if method == "GET" and m:
        if m.group(1) != "TZP-1001":
            return err(404, "NOT_FOUND")
        return 200, product_json(), {}

    # ---- OTP + session (public)
    if method == "POST" and path == "/v1/auth/otp/request":
        phone = (body or {}).get("phone", "")
        if not re.fullmatch(r"\+91[6-9][0-9]{9}", phone):
            return err(400, "INVALID_REQUEST")
        cid = "OTP_" + secrets.token_urlsafe(9)
        STATE["challenges"][cid] = phone
        return 202, {"challengeId": cid, "expiresInSeconds": 300, "resendAfterSeconds": 30, "requestId": "r"}, {}
    if method == "POST" and path == "/v1/auth/otp/verify":
        b = body or {}
        if b.get("challengeId") not in STATE["challenges"] or b.get("otp") != ARGS.otp:
            return err(400, "INVALID_OTP")
        gid = issue("GRANT_")
        STATE["grants"][gid] = True
        return 200, {"challengeId": b["challengeId"], "verified": True, "grantId": gid, "requestId": "r"}, {}
    if method == "POST" and path == "/v1/auth/session":
        gid = (body or {}).get("grantId")
        if not STATE["grants"].pop(gid, None):
            return err(401, "UNAUTHENTICATED")
        sid = "S" + secrets.token_hex(4)
        access, refresh = issue("AT."), issue("RT.")
        STATE["sessions"][sid] = {"active": True, "refresh": refresh, "old_refresh": set(), "access": {access}}
        return 200, {"customerId": "CUS_mock", "accessToken": access, "accessTokenExpiresIn": 900, "refreshToken": refresh,
                     "requestId": "r"}, {}
    if method == "POST" and path == "/v1/auth/refresh":
        rt = (body or {}).get("refreshToken")
        for sid, s in STATE["sessions"].items():
            if not s["active"]:
                continue
            if rt == s["refresh"] or (scen == "refresh-reuse-ok" and rt in s["old_refresh"]):
                s["old_refresh"].add(s["refresh"])
                access, refresh = issue("AT."), issue("RT.")
                s["refresh"] = refresh
                s["access"].add(access)
                return 200, {"accessToken": access, "accessTokenExpiresIn": 900, "refreshToken": refresh, "requestId": "r"}, {}
        return err(401, "UNAUTHENTICATED")

    # ---- customer surface (bearer)
    if path.startswith("/v1/customer") or path == "/v1/auth/logout":
        sid = auth_session(headers)
        if sid is None and not (scen == "anonymous-open" and path == "/v1/customer/profile" and method == "GET"):
            return err(401, "UNAUTHENTICATED")
        if path == "/v1/auth/logout" and method == "POST":
            if scen != "logout-not-revoking":
                STATE["sessions"][sid]["active"] = False
            return 204, None, {}
        if path == "/v1/customer/profile":
            if method == "GET":
                return 200, {"customerId": "CUS_mock", "displayName": STATE["display_name"],
                             "version": STATE["profile_version"], "requestId": "r"}, \
                    {"ETag": '"profile-%d"' % STATE["profile_version"]}
            if method == "PATCH":
                bad = if_match(headers, "profile-%d" % STATE["profile_version"])
                if bad:
                    return bad
                STATE["display_name"] = (body or {}).get("displayName")
                STATE["profile_version"] += 1
                return 200, {"customerId": "CUS_mock", "displayName": STATE["display_name"],
                             "version": STATE["profile_version"], "requestId": "r"}, {}
        if path == "/v1/customer/addresses":
            if method == "POST":
                if not headers.get("Idempotency-Key"):
                    return err(400, "INVALID_REQUEST")
                STATE["addr_seq"] += 1
                aid = "ADDR_mock%04d" % STATE["addr_seq"]
                a = dict(body or {}, addressId=aid, isDefault=not STATE["addresses"], version=1,
                         serviceability={"serviceable": True}, requestId="r")
                STATE["addresses"][aid] = a
                return 201, a, {}
            if method == "GET":
                return 200, {"items": list(STATE["addresses"].values()), "requestId": "r"}, {}
        m = re.fullmatch(r"/v1/customer/addresses/([^/]+)", path)
        if m:
            a = STATE["addresses"].get(m.group(1))
            if a is None:
                return err(404, "NOT_FOUND")
            if method == "GET":
                return 200, a, {"ETag": '"address-%d"' % a["version"]}
            if method == "DELETE":
                bad = if_match(headers, "address-%d" % a["version"])
                if bad:
                    return bad
                del STATE["addresses"][m.group(1)]
                return 204, None, {}
        m = re.fullmatch(r"/v1/customer/addresses/([^/]+)/default", path)
        if m and method == "PUT":
            a = STATE["addresses"].get(m.group(1))
            if a is None:
                return err(404, "NOT_FOUND")
            for x in STATE["addresses"].values():
                x["isDefault"] = False
            a["isDefault"] = True
            a["version"] += 1
            return 200, a, {}
        if path == "/v1/customer/cart":
            if method == "GET":
                return 200, cart_json(), {"ETag": '"cart-%d"' % STATE["cart"]["version"]}
            if method == "DELETE":
                bad = if_match(headers, "cart-%d" % STATE["cart"]["version"])
                if bad:
                    return bad
                STATE["cart"]["items"] = {}
                STATE["cart"]["version"] += 1
                return 200, cart_json(), {}
        m = re.fullmatch(r"/v1/customer/cart/items/([^/]+)", path)
        if m and method == "PUT":
            bad = if_match(headers, "cart-%d" % STATE["cart"]["version"])
            if bad:
                return bad
            q = (body or {}).get("quantity")
            if not isinstance(q, int) or not 1 <= q <= 20 or m.group(1) != "TZP-1001":
                return err(400, "INVALID_REQUEST")
            STATE["cart"]["items"][m.group(1)] = q
            STATE["cart"]["version"] += 1
            return 200, cart_json(), {}
        if path == "/v1/customer/checkout/quote" and method == "POST":
            bad = if_match(headers, "cart-%d" % STATE["cart"]["version"])
            if bad:
                return bad
            key = headers.get("Idempotency-Key")
            if not key:
                return err(428, "PRECONDITION_REQUIRED")
            addr = (body or {}).get("addressId")
            if addr not in STATE["addresses"]:
                return err(404, "NOT_FOUND")
            if key in STATE["quotes"]:
                return 200, STATE["quotes"][key], {}
            qid = "CHKQ_" + secrets.token_hex(6)
            items = [{"skuId": k, "quantity": q, "unitPricePaise": PRICE, "lineTotalPaise": PRICE * q}
                     for k, q in STATE["cart"]["items"].items()]
            sub = PRICE * sum(STATE["cart"]["items"].values())
            quote = {"quoteId": qid, "cartVersion": STATE["cart"]["version"], "addressId": addr, "items": items,
                     "itemCount": sum(STATE["cart"]["items"].values()), "distinctItemCount": len(items),
                     "subtotalPaise": sub, "currency": "INR", "createdAt": "x", "expiresAt": "y",
                     "moneyPreview": {"merchandiseSubtotalPaise": sub, "benefitDiscountPaise": 0, "payablePaise": sub},
                     "requestId": "r"}
            STATE["quotes"][key] = quote
            STATE["quotes_by_id"][qid] = dict(quote, _items=dict(STATE["cart"]["items"]))
            return 200, quote, {}
        if path == "/v1/customer/orders":
            if method == "POST":
                if scen == "order-500":
                    return err(500, "INTERNAL_ERROR")
                b = body or {}
                q = STATE["quotes_by_id"].get(b.get("quoteId"))
                if q is None or b.get("paymentMethod") != "COD":
                    return err(409, "QUOTE_INVALID")
                if b["quoteId"] in STATE["order_by_quote"] and scen != "replay-new-order":
                    o = STATE["orders"][STATE["order_by_quote"][b["quoteId"]]]
                    if scen == "stock-double-move":
                        STATE["stock"] -= sum(o["items"].values())
                    return 200, order_json(o), {}
                STATE["order_seq"] += 1
                o = {"id": "ORD_mock%04d" % STATE["order_seq"], "items": dict(q["_items"])}
                STATE["orders"][o["id"]] = o
                STATE["order_by_quote"][b["quoteId"]] = o["id"]
                if scen != "stock-not-moved":
                    STATE["stock"] -= sum(o["items"].values())
                if scen != "cart-not-cleared":
                    STATE["cart"]["items"] = {}
                    STATE["cart"]["version"] += 1
                return 200, order_json(o), {}
            if method == "GET":
                items = [{"orderId": o["id"], "status": "CONFIRMED", "paymentMethod": "COD",
                          "itemCount": sum(o["items"].values()), "subtotalPaise": 0, "createdAt": "x"}
                         for o in STATE["orders"].values()]
                return 200, {"items": items, "requestId": "r"}, {}
        m = re.fullmatch(r"/v1/customer/orders/([^/]+)", path)
        if m and method == "GET":
            o = STATE["orders"].get(m.group(1))
            return (200, order_json(o), {}) if o else err(404, "NOT_FOUND")
    return err(404, "NOT_FOUND", "no such mock route")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):  # keep test output quiet
        pass

    def handle_any(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        body = None
        if raw:
            try:
                body = json.loads(raw)
            except ValueError:
                body = None
        with LOCK:
            status, payload, extra = dispatch(self.command, u.path, parse_qs(u.query), self.headers, body)
        data = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for k, v in extra.items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_any


def main():
    global ARGS
    p = argparse.ArgumentParser()
    p.add_argument("--port-file", required=True)
    p.add_argument("--secrets-file")
    p.add_argument("--scenario", default="happy")
    p.add_argument("--otp", default="482915")
    ARGS = p.parse_args()
    reset()
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    with open(ARGS.port_file, "w") as f:
        f.write(str(srv.server_address[1]))
    srv.serve_forever()


if __name__ == "__main__":
    main()
