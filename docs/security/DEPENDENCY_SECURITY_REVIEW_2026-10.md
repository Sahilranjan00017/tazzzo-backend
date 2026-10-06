# Dependency security review, October 2026

Baseline: `main` at `c3306b6` (Spring Boot 3.3.5). Remediation: branch `fix/security-dependency-remediation` at `ab04e57`. Scan date 2026-10-06.

**Method.** Maven resolved the runtime and test dependency trees. Every exact artifact version was queried against the [OSV](https://osv.dev) database (which aggregates the GitHub Advisory Database and NVD), and each advisory was fetched and deduplicated across its CVE and GHSA aliases (one row per package and issue). Each row was then classified against the advisory's own preconditions, the code and configuration of this service, and, where it decides the question, a test that runs on both the old and the new versions. Severity is the GitHub advisory rating.

Classes: **CONFIRMED** (reachable here and reproduced), **PLAUSIBLE** (reachable, impact not reproduced or bounded by existing controls), **NOT_APPLICABLE** (a stated precondition is absent, verified in code or configuration), **UNKNOWN** (not provable either way at reasonable cost), **TEST_ONLY** (test classpath, not in the runtime image).

## 1. Result

| Scope | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Runtime, before | 9 | 38 | 37 | 14 | 98 |
| Runtime, after | 1 | 9 | 17 | 7 | 34 |
| Test-only, before | 0 | 4 | 3 | 1 | 8 |
| Test-only, after | 0 | 3 | 3 | 1 | 7 |

| Runtime applicability | Confirmed | Plausible | Not applicable | Unknown |
|---|---|---|---|---|
| Before | 2 | 4 | 91 | 1 |
| After | 0 | 0 | 33 | 1 |

**Every confirmed and plausible finding is fixed.** Every remaining runtime advisory is not applicable to this service, except one UNKNOWN (commons-lang3). The remainder sits in the Spring Framework 6.1, Spring Data 2024.0, Spring Boot 3.3 and Micrometer 1.13 lines, which no longer receive open-source fixes, plus logback, log4j-api and commons-lang3 (section 6).

## 2. Changes

| Component | Before | After | Why |
|---|---|---|---|
| spring-boot-starter-parent | 3.3.5 | 3.3.13 | Final 3.3 patch release: the compatible baseline for the overrides below. Brings Spring Framework 6.1.21, Spring Data 2024.0.13, Micrometer 1.13.15 and logback 1.5.18. |
| Tomcat (tomcat.version) | 10.1.31 | 10.1.60 | CVE-2026-24880 request smuggling (confirmed), the multipart DoS family, and 28 not-applicable advisories. |
| Jackson (jackson-bom.version) | 2.17.2 | 2.18.11 | CVE-2026-89407 ReDoS through Double request fields (confirmed), and 14 not-applicable 2.17 advisories. No 2.17 release carries the fixes. |
| Netty (netty.version) | 4.1.114.Final | 4.1.139.Final | 11 advisories, none applicable to the Redis client transport (TLS verification proven by RedisTlsVerificationIT). Same 4.1 line. |
| nimbus-jose-jwt | 9.37.3 | 9.37.4 | CVE-2025-53864 bounded JSON nesting (plausible). Same 9.37 line; the API is unchanged. |
| spring.servlet.multipart.enabled | true (Boot default) | false | No endpoint accepts multipart, so the multipart parser now has no entry point. |
| Consumer envelopes (`ConsumerDtos`) | order implied by Jackson 2.17 | `@JsonPropertyOrder` pins that same order | Jackson 2.18 orders record components by declaration. That would reorder 3 of the codebase's 331 records (NodeListResponse, ProductListResponse, ProductDetailResponse). Pinning keeps public responses byte-identical; ConsumerWireOrderTest guards it. |

Each override stays on the version line Spring Boot 3.3 manages: a patch release, or for Jackson the next 2.x minor, because no 2.17 fix exists. Each override is OSV-clean today. Remove an override once the Boot BOM manages an equal or newer version.

## 3. Confirmed and plausible findings

- **CVE-2025-48988** (high, `tomcat-embed-core` 10.1.31, plausible, fixed): Apache Tomcat - DoS in multipart upload. Boot's multipart resolver was on by default although no endpoint accepts multipart; bodies are capped at 64 KiB before parsing. Now unreachable (multipart disabled, MultipartDisabledIT) and fixed in 10.1.60. Fixed in: 10.1.42, 11.0.8, 9.0.106.
- **CVE-2025-52520** (high, `tomcat-embed-core` 10.1.31, plausible, fixed): Apache Tomcat Catalina is vulnerable to DoS attack through bypassing of size limits. Same multipart entry point as CVE-2025-48988 ("unlikely configurations"). Removed with multipart; fixed in 10.1.60. Fixed in: 10.1.43, 11.0.9, 9.0.107.
- **CVE-2026-24880** (high, `tomcat-embed-core` 10.1.31, confirmed, fixed): Apache Tomcat has an HTTP Request/Response Smuggling vulnerability. The connector parses chunked request bodies. 10.1.31 accepted five malformed chunk-extension forms and handed the body to the application (HttpRequestSmugglingIT, red before / green after). Exploitation needs a front-end proxy that frames those requests differently. Fixed in: 10.1.52, 11.0.20, 9.0.116.
- **CVE-2026-89407** (high, `jackson-core` 2.17.2, confirmed, fixed): jackson-core: ReDoS: quadratic backtracking in NumberInput.PATTERN_FLOAT via looksLikeValidNumber(). Binding a JSON string to Double runs NumberInput.looksLikeValidNumber. Reachable through the customer address latitude/longitude (any signed-in customer) and the admin classify confidence. One 60 KB request held the server 24.6 s on 2.17.2 and is rejected in milliseconds on 2.18.11 (AddressCoordinateParsingIT). Fixed in: 2.18.11, 2.21.7, 2.22.3.
- **CVE-2025-53864** (medium, `nimbus-jose-jwt` 9.37.3, plausible, fixed): Nimbus JOSE + JWT is vulnerable to DoS attacks when processing deeply nested JSON. Reachable: admin bearer tokens are parsed (header, then claims) before any signature check. Stack exhaustion did not reproduce on this runtime: 9.37.3 parsed 1,000,000-deep nesting in 222 ms with a 1 MB stack, and request headers are capped at 16 KB (about 5,000 levels). 9.37.4 rejects nesting deeper than 255 (GoogleOidcBoundedParsingTest, HumanAdminOidcIT). Fixed in: 10.0.2, 9.37.4.
- **CVE-2025-61795** (low, `tomcat-embed-core` 10.1.31, plausible, fixed): Apache Tomcat Vulnerable to Improper Resource Shutdown or Release. Multipart temp-file clean-up; same entry point. Removed with multipart; fixed in 10.1.60. Fixed in: 10.1.47, 11.0.12, 9.0.110.

## 4. Every advisory

| ID | Package | Severity | Class | Evidence | After this PR |
|---|---|---|---|---|---|
| CVE-2026-89407 | jackson-core 2.17.2 | High | Confirmed | Binding a JSON string to Double runs NumberInput.looksLikeValidNumber. Reachable through the customer address latitude/longitude (any signed-in customer) and the admin classify confidence. One 60 KB request held the server 24.6 s on 2.17.2 and is rejected in milliseconds on 2.18.11 (AddressCoordinateParsingIT). | fixed |
| CVE-2026-24880 | tomcat-embed-core 10.1.31 | High | Confirmed | The connector parses chunked request bodies. 10.1.31 accepted five malformed chunk-extension forms and handed the body to the application (HttpRequestSmugglingIT, red before / green after). Exploitation needs a front-end proxy that frames those requests differently. | fixed |
| CVE-2025-48988 | tomcat-embed-core 10.1.31 | High | Plausible | Boot's multipart resolver was on by default although no endpoint accepts multipart; bodies are capped at 64 KiB before parsing. Now unreachable (multipart disabled, MultipartDisabledIT) and fixed in 10.1.60. | fixed |
| CVE-2025-52520 | tomcat-embed-core 10.1.31 | High | Plausible | Same multipart entry point as CVE-2025-48988 ("unlikely configurations"). Removed with multipart; fixed in 10.1.60. | fixed |
| CVE-2025-53864 | nimbus-jose-jwt 9.37.3 | Medium | Plausible | Reachable: admin bearer tokens are parsed (header, then claims) before any signature check. Stack exhaustion did not reproduce on this runtime: 9.37.3 parsed 1,000,000-deep nesting in 222 ms with a 1 MB stack, and request headers are capped at 16 KB (about 5,000 levels). 9.37.4 rejects nesting deeper than 255 (GoogleOidcBoundedParsingTest, HumanAdminOidcIT). | fixed |
| CVE-2025-61795 | tomcat-embed-core 10.1.31 | Low | Plausible | Multipart temp-file clean-up; same entry point. Removed with multipart; fixed in 10.1.60. | fixed |
| CVE-2025-48924 | commons-lang3 3.14.0 | Medium | Unknown | No first-party use; transitive callers pass class names from code, not request input. Not exhaustively audited. | remains |
| CVE-2026-75595 | netty-handler 4.1.114.Final | Critical | Not Applicable | Server-side SNI handling; Netty is only the Redis client transport. | fixed |
| CVE-2025-24813 | tomcat-embed-core 10.1.31 | Critical | Not Applicable | The default servlet is not registered (Boot default) and is never write-enabled; no partial PUT. | fixed |
| CVE-2026-41293 | tomcat-embed-core 10.1.31 | Critical | Not Applicable | HTTP/2 is not enabled (server.http2.enabled unset). | fixed |
| CVE-2026-43512 | tomcat-embed-core 10.1.31 | Critical | Not Applicable | No Tomcat DIGEST authenticator or realm. | fixed |
| CVE-2026-43515 | tomcat-embed-core 10.1.31 | Critical | Not Applicable | No servlet security constraints are configured; authorisation lives in application filters. | fixed |
| CVE-2026-65182 | tomcat-embed-core 10.1.31 | Critical | Not Applicable | No servlet security constraints are configured. | fixed |
| CVE-2026-65905 | tomcat-embed-core 10.1.31 | Critical | Not Applicable | No Tomcat DIGEST authenticator. | fixed |
| CVE-2026-68525 | tomcat-embed-core 10.1.31 | Critical | Not Applicable | No Tomcat FORM authentication. | fixed |
| CVE-2026-47884 | spring-webmvc 6.1.14 | Critical | Not Applicable | No XsltView and no view rendering; REST controllers only. | remains |
| CVE-2026-68494 | jackson-core 2.17.2 | High | Not Applicable | Async (non-blocking) parser only; this servlet application uses the blocking parser. | fixed |
| CVE-2026-89425 | jackson-core 2.17.2 | High | Not Applicable | The DataInput-based parser is never used; Spring MVC parses InputStreams. | fixed |
| CVE-2026-54512 | jackson-databind 2.17.2 | High | Not Applicable | No polymorphic typing (@JsonTypeInfo, default typing, PolymorphicTypeValidator). | fixed |
| CVE-2026-54513 | jackson-databind 2.17.2 | High | Not Applicable | No polymorphic typing. | fixed |
| CVE-2026-68497 | jackson-databind 2.17.2 | High | Not Applicable | No javax.xml.datatype fields. | fixed |
| CVE-2026-91776 | jackson-databind 2.17.2 | High | Not Applicable | No @JsonTypeInfo. | fixed |
| CVE-2026-91777 | jackson-databind 2.17.2 | High | Not Applicable | No @JsonIdentityInfo. | fixed |
| CVE-2026-40984 | micrometer-core 1.13.6 | High | Not Applicable | The fix bounds HTTP-method tags in HTTP server observations; http.server.requests observation is disabled and first-party metrics use a bounded route enum. | remains |
| CVE-2026-42583 | netty-codec 4.1.114.Final | High | Not Applicable | Lz4 decoder is not in the Redis pipeline. | fixed |
| CVE-2026-59901 | netty-codec 4.1.114.Final | High | Not Applicable | Bzip2 decoder is not in the Redis pipeline. | fixed |
| CVE-2025-24970 | netty-handler 4.1.114.Final | High | Not Applicable | Native (OpenSSL) SSLEngine absent: no netty-tcnative on the classpath. | fixed |
| CVE-2026-44249 | netty-handler 4.1.114.Final | High | Not Applicable | IpSubnetFilter is not used. | fixed |
| CVE-2026-45416 | netty-handler 4.1.114.Final | High | Not Applicable | Server-side SNI handling; client transport only. | fixed |
| CVE-2026-50010 | netty-handler 4.1.114.Final | High | Not Applicable | The limiter client supplies no trust manager (JDK default X509ExtendedTrustManager, peer verification on), so the wrapping path is not taken. RedisTlsVerificationIT proves an untrusted CA and a wrong host name are refused, on the old and the new Netty. | fixed |
| CVE-2024-50379 | tomcat-embed-core 10.1.31 | High | Not Applicable | Requires a write-enabled default servlet and JSP compilation; neither exists. | fixed |
| CVE-2024-56337 | tomcat-embed-core 10.1.31 | High | Not Applicable | Mitigation note for CVE-2024-50379; same absent preconditions (and Java 21). | fixed |
| CVE-2025-48989 | tomcat-embed-core 10.1.31 | High | Not Applicable | HTTP/2 "made you reset"; HTTP/2 is not enabled. | fixed |
| CVE-2025-53506 | tomcat-embed-core 10.1.31 | High | Not Applicable | HTTP/2 is not enabled. | fixed |
| CVE-2025-55752 | tomcat-embed-core 10.1.31 | High | Not Applicable | No RewriteValve. | fixed |
| CVE-2026-24734 | tomcat-embed-core 10.1.31 | High | Not Applicable | Tomcat Native/OCSP is not used; TLS terminates at the load balancer. | fixed |
| CVE-2026-34483 | tomcat-embed-core 10.1.31 | High | Not Applicable | No JsonAccessLogValve (Tomcat access log not configured). | fixed |
| CVE-2026-34487 | tomcat-embed-core 10.1.31 | High | Not Applicable | No Tomcat clustering / cloud membership. | fixed |
| CVE-2026-41284 | tomcat-embed-core 10.1.31 | High | Not Applicable | No WebDAV servlet. | fixed |
| CVE-2026-42498 | tomcat-embed-core 10.1.31 | High | Not Applicable | Tomcat's WebSocket client is not used. | fixed |
| CVE-2026-43513 | tomcat-embed-core 10.1.31 | High | Not Applicable | No LockOutRealm or any Tomcat realm. | fixed |
| CVE-2025-22235 | spring-boot 3.3.5 | High | Not Applicable | Needs Spring Security EndpointRequest; not used. | fixed |
| CVE-2026-40973 | spring-boot 3.3.5 | High | Not Applicable | Needs server.servlet.session.persistent=true and a local attacker on the host; neither. | remains |
| CVE-2026-22733 | spring-boot-starter-actuator 3.3.5 | High | Not Applicable | Cloud Foundry actuator endpoints with Spring Security; no actuator endpoint is exposed and Spring Security is not used. | remains |
| CVE-2026-41716 | spring-data-commons 3.3.5 | High | Not Applicable | Fed by Spring Data web binding and repository property paths; no repositories, no Pageable/Sort/projection binding. | remains |
| CVE-2026-41717 | spring-data-mongodb 4.3.5 | High | Not Applicable | No Spring Data MongoDB repositories or @Query methods. | remains |
| CVE-2025-41249 | spring-core 6.1.14 | High | Not Applicable | Needs Spring Security method security; Spring Security is not used. | remains |
| CVE-2026-41850 | spring-expression 6.1.14 | High | Not Applicable | No SpEL evaluation of user input (no expression parser in first-party code). | remains |
| CVE-2026-41842 | spring-webmvc 6.1.14 | High | Not Applicable | Needs file-system static resources with versioned-resource support; none configured. | remains |
| CVE-2026-41845 | spring-webmvc 6.1.14 | High | Not Applicable | JavaScriptUtils is not used. | remains |
| CVE-2024-12798 | logback-core 1.5.11 | Medium | Not Applicable | JaninoEventEvaluator; Janino is absent. | fixed |
| CVE-2025-11226 | logback-core 1.5.11 | Medium | Not Applicable | Needs Janino on the classpath and attacker write access to the logback configuration; neither. | remains |
| CVE-2026-18401 | jackson-core 2.17.2 | Medium | Not Applicable | Async parser only; the blocking parser enforces maxNumberLength (JsonInputBoundsIT). | fixed |
| CVE-2026-19032 | jackson-databind 2.17.2 | Medium | Not Applicable | No java.nio.file.Path bound from JSON. | fixed |
| CVE-2026-54514 | jackson-databind 2.17.2 | Medium | Not Applicable | No InetSocketAddress bound from JSON (the InetAddress uses are configuration parsers). | fixed |
| CVE-2026-54515 | jackson-databind 2.17.2 | Medium | Not Applicable | Case-insensitive property matching is not enabled. | fixed |
| CVE-2026-59888 | jackson-databind 2.17.2 | Medium | Not Applicable | No @JsonIgnore and no naming strategy. | fixed |
| CVE-2026-77310 | jackson-databind 2.17.2 | Medium | Not Applicable | No InetAddress bound from JSON. | fixed |
| CVE-2026-83557 | jackson-databind 2.17.2 | Medium | Not Applicable | No polymorphic typing. | fixed |
| CVE-2025-58057 | netty-codec 4.1.114.Final | Medium | Not Applicable | Compression decoders are not in the Redis pipeline. | fixed |
| CVE-2024-47535 | netty-common 4.1.114.Final | Medium | Not Applicable | Windows-only; Linux containers. | fixed |
| CVE-2025-25193 | netty-common 4.1.114.Final | Medium | Not Applicable | Windows-only; Linux containers. | fixed |
| CVE-2026-75596 | netty-handler 4.1.114.Final | Medium | Not Applicable | Server-side SNI handling; client transport only. | fixed |
| CVE-2026-49844 | log4j-api 2.23.1 | Medium | Not Applicable | MapMessage JSON serialisation is not used; the Log4j API only bridges to SLF4J. | remains |
| CVE-2025-31650 | tomcat-embed-core 10.1.31 | Medium | Not Applicable | HTTP/2 priority header; HTTP/2 is not enabled. | fixed |
| CVE-2025-49124 | tomcat-embed-core 10.1.31 | Medium | Not Applicable | Windows installer only. | fixed |
| CVE-2025-49125 | tomcat-embed-core 10.1.31 | Medium | Not Applicable | No Pre/PostResources are configured. | fixed |
| CVE-2025-55668 | tomcat-embed-core 10.1.31 | Medium | Not Applicable | Session fixation via the RewriteValve; no RewriteValve and no HTTP sessions. | fixed |
| CVE-2025-66614 | tomcat-embed-core 10.1.31 | Medium | Not Applicable | No TLS connector or virtual hosts in Tomcat. | fixed |
| CVE-2026-25854 | tomcat-embed-core 10.1.31 | Medium | Not Applicable | No LoadBalancerDrainingValve. | fixed |
| CVE-2026-41001 | spring-boot-autoconfigure 3.3.5 | Medium | Not Applicable | Embedded Artemis is not used. | remains |
| CVE-2026-41711 | spring-data-commons 3.3.5 | Medium | Not Applicable | No Sort parameter parsing. | remains |
| CVE-2026-41721 | spring-data-commons 3.3.5 | Medium | Not Applicable | @ProjectedPayload is not used. | remains |
| CVE-2026-41719 | spring-data-keyvalue 3.3.5 | Medium | Not Applicable | No KeyValue repositories. | remains |
| CVE-2026-41696 | spring-data-mongodb 4.3.5 | Medium | Not Applicable | No Spring Data MongoDB repositories or @Query methods. | remains |
| CVE-2026-41851 | spring-expression 6.1.14 | Medium | Not Applicable | No SpEL evaluation of user input. | remains |
| CVE-2025-41234 | spring-web 6.1.14 | Medium | Not Applicable | ContentDisposition with a user-supplied filename is not used. | fixed |
| CVE-2025-41242 | spring-webmvc 6.1.14 | Medium | Not Applicable | Spring states applications deployed on Tomcat are not vulnerable. | remains |
| CVE-2026-22737 | spring-webmvc 6.1.14 | Medium | Not Applicable | No script template views. | remains |
| CVE-2026-22745 | spring-webmvc 6.1.14 | Medium | Not Applicable | Needs file-system static resources on Windows. | remains |
| CVE-2026-41841 | spring-webmvc 6.1.14 | Medium | Not Applicable | Needs several authenticated resource handlers sharing a cache; not configured. | remains |
| CVE-2026-41843 | spring-webmvc 6.1.14 | Medium | Not Applicable | Needs file-system static resources with versioned-resource support; none configured. | remains |
| CVE-2026-41844 | spring-webmvc 6.1.14 | Medium | Not Applicable | No '/**' view-name mapping; no view resolution. | remains |
| CVE-2026-41846 | spring-webmvc 6.1.14 | Medium | Not Applicable | No JSP. | remains |
| CVE-2026-41853 | spring-webmvc 6.1.14 | Medium | Not Applicable | Needs an application accepting multipart behind a multipart-parsing WAF/proxy; no endpoint accepts multipart (now disabled). | remains |
| CVE-2024-12801 | logback-core 1.5.11 | Low | Not Applicable | Needs an attacker-modified XML logback configuration. | fixed |
| CVE-2026-10532 | logback-core 1.5.11 | Low | Not Applicable | SimpleSocketServer / SimpleSSLSocketServer are not used. | remains |
| CVE-2026-1225 | logback-core 1.5.11 | Low | Not Applicable | Needs attacker write access to the logback configuration. | remains |
| CVE-2026-9828 | logback-core 1.5.11 | Low | Not Applicable | SimpleSocketServer / SimpleSSLSocketServer are not used. | remains |
| CVE-2025-31651 | tomcat-embed-core 10.1.31 | Low | Not Applicable | No RewriteValve. | fixed |
| CVE-2025-46701 | tomcat-embed-core 10.1.31 | Low | Not Applicable | No CGI servlet. | fixed |
| CVE-2025-55754 | tomcat-embed-core 10.1.31 | Low | Not Applicable | Windows console only; Linux containers without a console. | fixed |
| CVE-2026-43514 | tomcat-embed-core 10.1.31 | Low | Not Applicable | No AJP connector. | fixed |
| CVE-2025-22233 | spring-context 6.1.14 | Low | Not Applicable | No DataBinder disallowedFields. | fixed |
| CVE-2026-41848 | spring-core 6.1.14 | Low | Not Applicable | AntPathMatcher never receives attacker-supplied patterns (not used in first-party code; MVC uses PathPatternParser). | remains |
| CVE-2026-41852 | spring-expression 6.1.14 | Low | Not Applicable | No SpEL evaluation of user input. | remains |
| CVE-2026-22735 | spring-webmvc 6.1.14 | Low | Not Applicable | No Server-Sent Events. | remains |
| CVE-2026-22741 | spring-webmvc 6.1.14 | Low | Not Applicable | Needs resource-chain caching with encoded resources; not configured. | remains |
| CVE-2024-57699 | json-smart 2.5.1 | High | Test Only | json-smart via spring-boot-starter-test; not in the runtime image. | fixed |
| CVE-2026-54399 | httpcore5 5.2.5 | High | Test Only | httpcore5 (test HTTP client); not in the runtime image. | remains |
| CVE-2026-54428 | httpcore5-h2 5.2.5 | High | Test Only | httpcore5-h2 (test HTTP client); not in the runtime image. | remains |
| CVE-2026-24400 | assertj-core 3.25.3 | High | Test Only | AssertJ isXmlEqualTo; not used, test scope only. | remains |
| CVE-2024-25710 | commons-compress 1.24.0 | Medium | Test Only | commons-compress via Testcontainers; not in the runtime image. | remains |
| CVE-2024-26308 | commons-compress 1.24.0 | Medium | Test Only | commons-compress via Testcontainers; not in the runtime image. | remains |
| CVE-2026-64607 | httpclient5 5.3.1 | Medium | Test Only | httpclient5 (test HTTP client); not in the runtime image. | remains |
| CVE-2024-31573 | xmlunit-core 2.9.1 | Low | Test Only | xmlunit (test only); not in the runtime image. | remains |

## 5. Found during this review, not a dependency issue

**Malformed or unsupported request bodies return 500 instead of the documented 400 or 415 on some customer and auth endpoints.** The OTP, session and customer-profile controller advices have a catch-all `Exception` handler and run at highest precedence. Because they map neither `HttpMessageNotReadableException` nor `HttpMediaTypeNotSupportedException`, the framework 4xx becomes `500 INTERNAL` with an ERROR log line. **Observed** for `POST /v1/auth/otp/request` (an unreadable body and a multipart content type both returned 500 during this review). **Inferred from code, not exercised** for `POST /v1/auth/session` and `/refresh` and the customer profile write, which have the same handler shape. The delivery-slot advice has the same shape but its controller takes no body. The OpenAPI contract documents `400 OtpBadRequest` ("Malformed request…") and no 500. Impact: any unauthenticated caller can produce unlimited 500s and ERROR log lines, which adds alert noise and log cost. No data is exposed; the body is the generic `internal error`. Jackson rejected every hostile shape correctly; this is error mapping, not parsing. It is **not changed here** to keep this PR to dependency remediation. Recommended follow-up: one small PR mapping both exceptions in those three advices to their documented 400 and 415 bodies, with tests.

**Update (error-handling hardening follow-up, branch `fix/http-error-handling-hardening`):** the post-merge review found the defect wider than the three advices above: all 12 controller-scoped advices with an `Exception` catch-all (13 advices, counting the staff-support one that falls through to `ApiExceptionHandler`) were affected, and an `Accept` header the route cannot produce also turned ordinary error responses into 500s with a multi-line WARN stack trace. That PR adds `ClientRequestErrors`, a single classifier for these request-shape failures that every catch-all consults first. It answers a malformed body with 400, an unsupported `Content-Type` with 415 and an unacceptable `Accept` with 406. Each response keeps its domain's documented shape and code values, and errors are logged as one WARN line. Every error response also fixes its `Content-Type` to `application/json`, so `Accept` can no longer change an error's outcome. A 58-request probe (the 44 from this review plus 14 more) went from 31 responses with status 500 and 15 requests logging an ERROR line or a stack trace to none of either.

## 6. Supported-platform status and residual risk

- **Not a supported platform.** Spring Boot 3.3 left open-source support on 2025-06-30. 3.3.13 is its final release, and the Spring Framework 6.1, Spring Data 2024.0 and Micrometer 1.13 lines it manages receive no further open-source fixes. Patching individual libraries reduces today's exposure; it does **not** restore official support. A planned migration to a supported line (Spring Boot 4.1, open-source support to 2027-07-31) is required before production. That is a separate workstream: Dependabot PR #83 (4.1.1) does not compile and would silently split `spring.data.mongodb.*` from Boot's `spring.mongodb.*`.
- **Remaining advisories.** 34 runtime advisories remain after this PR (1 critical, 9 high, 17 medium, 7 low). Each is classified NOT_APPLICABLE in section 4 with the absent precondition named, except CVE-2025-48924 in commons-lang3 (medium severity, classified UNKNOWN; low likelihood, since no first-party code calls the affected method). Every remaining HIGH or CRITICAL is a Spring Framework 6.1 view, static-resource, SpEL or method-security feature, a Spring Data repository or web-binding feature, a Spring Boot Cloud Foundry, Artemis or persistent-session feature, or a Micrometer HTTP-server observation this service disables. None is reachable as configured, and **re-verify** these verdicts when that configuration changes.
- **Test-only advisories do not ship.** Seven remain: assertj-core, commons-compress (2), httpclient5, httpcore5, httpcore5-h2 and xmlunit-core. The eighth, json-smart, was fixed by Boot 3.3.13. The runtime image's library directory was checked to contain none of them.
- **This PR is not a production approval.** The unsupported platform remains, and the error-mapping follow-up in section 5 is open.

## 7. Compatibility evidence

- **Full suite on `ab04e57`:** 3,227 tests, 0 failures, 0 errors, 0 skipped. Main has 3,200; this PR adds 27.
- **Required classes:** all pass.
  - ModuleBoundaryTest 82, IndexContractIT 12, MigrationRegistryTest 5, DatastorePrivilegeIT 14, DatabaseDocsConsistencyTest 7, RoleFilesTest 3.
  - HumanAdminOidcIT 12, GoogleOidcAuthenticatorTest 41, AdminAuditEventsIT 34.
  - OrderPlaceCodIT 51, CustomerJourneyE2EIT 1, AccountDeletionIT 6, AddressCreateIdempotencyHttpIT 8, AddressIdempotencyRaceIT 3.
  - ApiContractParityIT 2, SecurityHeadersIT 1, RequestBodyLimitIT 8, PlatformFilterOrderTest 1, ErrorSanitizationIT 4.
- **Database invariants unchanged.** 55 collections; migrations V0001–V0015 (plus the disabled V0101 and V0102); 75 indexes; released migration checksums identical. The runtime role file and the generated admin OpenAPI regenerate byte-identical.
- **API serialization preserved.** The serialized property order of every record (331) is identical between Jackson 2.17.2 and the patched build, and the generated OpenAPI is byte-identical to `main` (ConsumerWireOrderTest).
- **Secret scan clean.** 991 tracked files.
- **Container image.** It builds and runs as uid 10001 with no configuration baked in. There are 0 secret-pattern hits across its 1,124 application files and in its build history, and 0 test-only libraries. The packaged libraries are tomcat-embed-core 10.1.60, jackson 2.18.11, netty 4.1.139.Final, nimbus-jose-jwt 9.37.4 and spring-webmvc 6.1.21.

Regression tests added by this PR, run on the old versions (main) and on this branch:

| Test | On main (old versions) | On this branch |
|---|---|---|
| GoogleOidcBoundedParsingTest (6) | 2 fail: 5,500 and 100,000-deep claims authenticate | 6 pass |
| HumanAdminOidcIT, nested-token case | fails: a nested token authenticates over HTTP | passes (401) |
| AddressCoordinateParsingIT (2) | ReDoS case fails: one request took 24.6 s | 2 pass (milliseconds) |
| HttpRequestSmugglingIT (6) | 5 fail: malformed chunk extensions accepted | 6 pass (400) |
| MultipartDisabledIT (2) | 2 fail: multipart parser active | 2 pass |
| JsonInputBoundsIT (3) | 3 pass (existing protection; guard) | 3 pass |
| RedisTlsVerificationIT (4) | 4 pass (verification already enforced; proves CVE-2026-50010 not applicable) | 4 pass |
| ConsumerWireOrderTest (3) | n/a (guards the Jackson upgrade) | 3 pass |

## 8. Rollback

1. Revert the PR (one squash commit). That returns `pom.xml`, `application.yml`, `ConsumerDtos.java` and the tests to `main`. No data, migration, index or schema is touched, so nothing else needs undoing.
2. To roll back one component only, delete its property override (`tomcat.version`, `jackson-bom.version` or `netty.version`) or restore the previous nimbus version. Each is independent.
3. Re-enabling multipart is `spring.servlet.multipart.enabled: true`. Nothing depends on it today.
4. A deployed image rolls back by redeploying the previous image tag. The datastore contract, migrations and the 55/15/75 schema are unchanged.
