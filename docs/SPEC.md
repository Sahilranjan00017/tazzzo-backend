# Build Spec — tazzzo-catalog-service (from Implementation Contract V1)
Authoritative sources (READ THEM):
- /Users/user/Documents/CEO/Final Story/Tazzzo Research/Tazzzo_Implementation_Contract_V1.md
- docs/contract_attack.js  (the EXACT products validator + executed test semantics to port)
Rules that may not be violated: contract-first (a contradiction found while coding = STOP and
report, never work around); C-3 event-before-state ordering; C-4 every mutation requires an
event payload at compile time; T-RULE-1 expected-rejection tests assert the failing clause.

## Stack
Java 21 (build with JAVA_HOME=$(/usr/libexec/java_home -v 21)), Spring Boot 3.3.5,
spring-data-mongodb, Testcontainers `MongoDBContainer("mongo:7")` (single-node replica set →
multi-doc transactions work). No Lombok. Use Java records where natural. Package root:
`com.tazzzo.catalog`.

## Packages & classes (exact names — tests depend on them)
com.tazzzo.catalog.CatalogApplication                     — @SpringBootApplication
com.tazzzo.catalog.schema.SchemaBootstrap                 — on startup (property-gated) creates
  collections + validators + indexes IDEMPOTENTLY. Validators must byte-match the semantics in
  docs/contract_attack.js for `products` (oneOf shape rule, additionalProperties:false, enums,
  maxItems, patterns, generated language list [en,hi] and empty ext {}). Also create:
  gtin_registry, identity_keys, brands, product_events, classification_history, evidence,
  evidence_links, work_queue, offers_current. Public method `void bootstrap(MongoDatabase db)`
  so tests can call it directly.
com.tazzzo.catalog.domain.*                               — thin document model. Products are
  handled as `org.bson.Document` via a ProductDocuments factory (records for inputs):
  record ProductDraft(String id, String productType, String identityType, String internalKey,
    List<GtinBinding> gtins, String brandCode, String title, String verticalId, String releaseId,
    String classificationStatus, Map<String,Object> attributes, List<String> evidenceRefs,
    List<BundleComponent> bundleContents)
  record GtinBinding(String value, String market)
  record BundleComponent(String componentProductId, int qty, String verticalIdSnapshot)
com.tazzzo.catalog.events.EventPayload                    — record EventPayload(String type,
  String productId, Map<String,Object> detail). REQUIRED parameter on every write-path call.
com.tazzzo.catalog.repo.WritePath                         — THE single write path. Constructor: (MongoDatabase). Methods (all take ClientSession where transactional):
    void insertWithEvent(ClientSession s, String collection, Document doc, EventPayload e)
    long casUpdateWithEvent(ClientSession s, String collection, String id, long expectedVersion,
                            Bson update, EventPayload e)   // returns modifiedCount; 0 => CasConflictException
  RULE C-3: the event insert into product_events happens BEFORE the state write, same session.
  No service may write via MongoTemplate directly (only WritePath uses it).
com.tazzzo.catalog.tx.MintService                         — T1: public String mint(ProductDraft d)
  In ONE transaction: identity gate (identity_keys insertOne for internal; gtin_registry insertOne
  per binding for gtin — E11000 => IdentityCollisionException), products insert, initial
  classification_history row, MINTED event, work_queue item if verticalId is TZV-UNCLASSIFIED or
  TZV-SCOPE-BLOCKED.
com.tazzzo.catalog.tx.MergeService                        — T2: public void startMerge(String loserId,
  String survivorId): one txn — assert both lifecycle=active (else IllegalStateException); set both
  lifecycle=merging (CAS); identity_keys redirect if internal (status=redirected, redirected_to);
  MERGE_STARTED event carrying repoint manifest; insert ONE work_queue outbox doc
  {type:"merge_repoint", loser, survivor, manifest:[offers, bundles], completed:[], status:"pending"}.
  public void runFinalizer(): claims pending outbox items (lease via CAS), executes repoint jobs
  idempotently (offers_current product_id rewrite w/ survivor-wins on duplicate key; bundle_contents
  component rewrite w/ qty-merge dedupe), marks each completed on the outbox doc, re-scans for
  stragglers, then CAS-flips loser→{lifecycle:merged, merged_into:survivor} and survivor→active,
  emits MERGE_COMPLETED. Safe to call after a "crash" (idempotent resume) — this IS K-1.
com.tazzzo.catalog.tx.PublishService                      — T3: public void publishClaim(String productId,
  String attrKey, List<String> evidenceIds): one txn — $inc fence on EACH evidence doc FIRST, then
  read them IN THE SAME TXN and assert validity=="active" && payloadState=="readable" (else
  EvidenceGateException), then CAS-update product attributes.<attrKey>_published=true, CLAIM_PUBLISHED
  event. The fence write is what makes concurrent retraction conflict (write-set intersection).
com.tazzzo.catalog.tx.ClassifyService                     — T4: public void classify(String productId,
  String verticalId, String releaseId, String status, double confidence, List<String> evidenceIds):
  one txn — history insert, projection CAS update, evidence_links upserts, CLASSIFIED event,
  work item if holding vertical.
com.tazzzo.catalog.tx.GtinBindService                     — T5: public void bind(String productId,
  String gtin, String market): one txn — registry open binding (close any open one for same market),
  product gtins cache update, GTIN_BOUND event.
com.tazzzo.catalog.tx.BundleService                       — T7: public void writeBundle(ProductDraft bundle):
  one txn — read each component in-session, assert lifecycle=="active" && productType!="bundle"
  (else BundleComponentException), compute browse_verticals union, insert/update bundle doc, event.
(T6 release service may be a minimal ReleaseService with status publishing→active; low priority.)
Exceptions in com.tazzzo.catalog.tx: CasConflictException, IdentityCollisionException,
EvidenceGateException, BundleComponentException — runtime exceptions.
Transactions: use MongoTransactionManager or explicit ClientSession with session.withTransaction.
IMPORTANT Mongo detail: inserts/updates inside a txn against a collection must NOT create it —
SchemaBootstrap pre-creates ALL collections (txns cannot create collections implicitly on Mongo 7
with validators anyway; be explicit).

## Tests (src/test/java/com/tazzzo/catalog/…) — Testcontainers, @Testcontainers/@Container static
Shared base: AbstractMongoIT starts MongoDBContainer("mongo:7"), sets spring.data.mongodb.uri via
@DynamicPropertySource, runs SchemaBootstrap.
1. ValidatorContractIT — port of docs/contract_attack.js G/I battery: AT1-a..d (incl. the
   zero-drift metadata diff), G-6, G-16/G-16b, I-1,I-2,I-3,I-3b,I-6(bypass accepted),I-7,I-9,
   I-10,I-11,I-12,I-13,I-14, R-1/R-2, U-1..U-4. T-RULE-1: every expected rejection asserts the
   failing clause token appears in the server's errInfo/message (use MongoWriteException details).
2. TransactionAtomicityIT —
   TX-T1a: force mint failure AFTER identity key insert (e.g. draft violating validator) → assert
     NO identity_keys doc, NO product, NO event (atomic absence).
   TX-T1b: happy mint → product + key + history + event all present.
   TX-T7a: bundle referencing a discontinued component → BundleComponentException, nothing written.
   TX-T4a: classify with invalid status → rejected, no history row, projection untouched.
3. MergeCrashIT (K-1) — startMerge commits; DO NOT run finalizer (simulated crash): assert both
   products lifecycle=merging, outbox pending, offers still on loser. Then runFinalizer(): assert
   offers repointed (survivor-wins dedupe), loser lifecycle=merged w/ merged_into, survivor active,
   outbox completed, MERGE_COMPLETED event exists. Call runFinalizer() AGAIN → idempotent no-op.
4. PublishFenceIT — evidence active+readable → publish succeeds; then retract evidence (validity
   flip) and attempt publish of another claim citing it → EvidenceGateException. CONCURRENCY: two
   threads, one publishing (with a latch-induced delay inside txn if feasible) and one retracting —
   assert final state NEVER has claim published against retracted evidence (retry loop acceptable;
   document the mechanism in comments). If in-txn latching proves flaky, a deterministic
   sequential version of the conflict (fence causes WriteConflict on simultaneous sessions) is
   acceptable — but must actually use two ClientSessions overlapping.
Surefire: tests must run with the container; total runtime target < 8 min.

## Definition of done
`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q test` exits 0 with ALL the above tests
present and passing. No @Disabled. No weakened validators. Contract contradictions reported, not
patched around.
