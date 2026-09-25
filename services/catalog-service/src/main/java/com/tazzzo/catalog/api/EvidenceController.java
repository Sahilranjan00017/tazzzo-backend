package com.tazzzo.catalog.api;

import com.tazzzo.catalog.api.ApiDtos.*;
import com.tazzzo.catalog.tx.EvidenceContractException;
import com.tazzzo.catalog.tx.EvidenceService;
import com.tazzzo.catalog.tx.TaintService;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Date;

/**
 * Evidence transport. Contains NO catalogue rules: immutability, idempotency, validity
 * transitions and cascade enqueueing all live in EvidenceService / TaintService.
 * Catalogue stores a payload REFERENCE — raw bytes are refused here and never persisted.
 */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;
    private final TaintService taintService;

    public EvidenceController(EvidenceService evidenceService, TaintService taintService) {
        this.evidenceService = evidenceService;
        this.taintService = taintService;
    }

    @PostMapping
    public ResponseEntity<EvidenceResponse> create(@RequestBody CreateEvidenceRequest body) {
        if (body.payload() != null) {
            throw new EvidenceContractException("PAYLOAD_NOT_ACCEPTED",
                    "send bytes to the media service and reference them via payloadRef");
        }
        Document ref = body.payloadRef() == null ? null
                : new Document("store", body.payloadRef().store())
                        .append("object_id", body.payloadRef().objectId())
                        .append("sha256", body.payloadRef().sha256());
        Date observedAt = body.observedAt() == null ? null
                : Date.from(Instant.parse(body.observedAt()));   // DateTimeParseException -> 400
        EvidenceService.CreateOutcome outcome = evidenceService.create(body.id(),
                body.evidenceType(), body.source(), body.sourceVersion(), ref,
                body.excerpt(), body.url(), observedAt);
        HttpStatus status = outcome == EvidenceService.CreateOutcome.CREATED
                ? HttpStatus.CREATED : HttpStatus.OK;   // identical replay -> 200
        return ResponseEntity.status(status).body(read(body.id()));
    }

    @PostMapping("/{id}/retract")
    public ResponseEntity<RetractAcceptedResponse> retract(@PathVariable String id,
                                                           @RequestBody RetractEvidenceRequest body) {
        taintService.retractEvidence(id, body.to());   // validity flip + cascade item, one transaction
        String validity = evidenceService.find(id).getString("validity");
        return ResponseEntity.accepted()
                .body(new RetractAcceptedResponse(id, validity, "queued"));
    }

    @GetMapping("/{id}")
    public EvidenceResponse get(@PathVariable String id) {
        return read(id);
    }

    private EvidenceResponse read(String id) {
        Document d = evidenceService.find(id);
        if (d == null) throw new NotFoundException("no such evidence: " + id);
        Document ref = d.get("payload_ref", Document.class);
        return new EvidenceResponse(d.getString("_id"), d.getString("evidence_type"),
                d.getString("source"), d.getString("source_version"),
                ref == null ? null : new PayloadRefDto(ref.getString("store"),
                        ref.getString("object_id"), ref.getString("sha256")),
                d.getString("excerpt"), d.getString("url"),
                d.getString("validity"), d.getString("payload_state"),
                d.getDate("observed_at") == null ? null
                        : d.getDate("observed_at").toInstant().toString());
        // NOTE: `fence` and other internal fields are never exposed.
    }
}
