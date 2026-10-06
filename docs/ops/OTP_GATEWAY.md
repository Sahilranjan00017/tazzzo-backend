# OTP delivery: the HTTPS gateway adapter

`tazzzo.customer-auth.otp.provider-mode` selects how a login code reaches a phone:

| Mode | Behaviour |
|---|---|
| unset (default) | no provider: `POST /v1/auth/otp/request` fails closed with 503 — a code is never silently discarded |
| `LOGGING` | development only: logs a masked-phone event, never the code |
| `HTTP` | `HttpOtpDeliveryProvider`: POSTs to an SMS gateway you operate (or front your vendor with) |

## Contract of `HTTP` mode
`POST <url>` with `Content-Type: application/json` and the configured credential header:

```json
{"to":"+919876543210","message":"482916 is your Tazzzo verification code. It expires in 5 minutes. Do not share it with anyone.","sender":"TAZZZO"}
```
Any `2xx` means "accepted for delivery"; anything else fails the OTP request (503, no challenge activated, a previous working code is untouched).

| Property (`tazzzo.customer-auth.otp.http.*`) | Rule |
|---|---|
| `url` | required; `https` only (plain `http` only for a loopback host); no userinfo |
| `auth-header-value` | required, from the secret store (env var), never committed; never logged or printed |
| `auth-header-name` | default `Authorization` |
| `message-template` | must contain `{otp}`; `{minutes}` optional; ≤ 320 chars |
| `sender` | optional, 1..32 of letters/digits/space/`_`/`-` |
| `connect-timeout-millis` / `read-timeout-millis` | defaults 2000 / 3000; their sum must be below `delivery-timeout-seconds` |

All of it is validated at **startup** (the process refuses to start on an unsafe or incomplete gateway configuration), not at the first customer request.

## Safety properties (tested)
- Redirects are never followed: a 3xx cannot bounce the code or the credential to another host.
- The code, the phone, the URL, the credential and the gateway's response body appear in no log line, exception message or metric. Failures are fixed, bounded reasons.
- The only metric is `otp_gateway_send{outcome=accepted|rejected|timeout|transport_error|redirect}`.

## External gate (UNVERIFIED)
No real SMS vendor was contacted. Still required before production: choosing the vendor and fronting it with this contract (or writing a vendor-specific adapter), the **India DLT** sender/template registration (the template text above must match the registered template), delivery-receipt handling, a failover vendor, and a cost/abuse budget (per-phone and per-IP request limits already exist; vendor spend caps do not). Staging verification against the real gateway is open.
