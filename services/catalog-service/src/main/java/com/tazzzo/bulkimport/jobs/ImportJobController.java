package com.tazzzo.bulkimport.jobs;

import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/v1/admin/imports/jobs}: asynchronous, resumable product imports of any size. Lives under the bulk-import
 * prefix so the request-body limit for files applies ({@code tazzzo.http.bulk-import-max-request-body-bytes}); a file
 * larger than that is split across several {@code rows} requests. Same authorisation as the other admin writes.
 *
 * <pre>
 * POST   /jobs                      {"kind":"products","note":"..."}            → 201 job
 * POST   /jobs/{id}/rows            text/csv (streamed) or {"rows":[...]}       → counts
 * PUT    /jobs/{id}/rows/{row}      one product row (a correction)              → job
 * POST   /jobs/{id}/validate        {"version":n}?                              → job (VALIDATING)
 * POST   /jobs/{id}/apply           {"version":n}?  explicit approval           → job (APPLYING)
 * POST   /jobs/{id}/resume          {"version":n}?                              → job (APPLYING from the cursor)
 * POST   /jobs/{id}/cancel          {"version":n}?                              → job (CANCELLED)
 * GET    /jobs?status&after&limit                                               → jobs
 * GET    /jobs/{id}                                                             → job
 * GET    /jobs/{id}/rows?from&limit                                             → rows with their outcomes
 * GET    /jobs/{id}/errors.csv                                                  → every negative verdict
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/imports/jobs")
class ImportJobController {

    record CreateJobRequest(String kind, String note) { }

    record VersionedRequest(Long version) { }

    record RowsRequest(List<CreateProductRequest> rows) { }

    record JobView(String id, String kind, String status, String note, Map<String, String> createdBy, Map<String, String> approvedBy,
                   long rowsTotal, long nextRow, Map<String, Long> counts, int attemptCount, String lastError, String createdAt,
                   String updatedAt, String startedAt, String finishedAt, long version) {
        static JobView of(ImportJob j) {
            return new JobView(j.id(), j.kind().name().toLowerCase(), j.status().name(), j.note(), actor(j.createdBy()),
                    actor(j.approvedBy()), j.rowsTotal(), j.nextRow(), j.counts().asMap(), j.attemptCount(), j.lastError(),
                    str(j.createdAt()), str(j.updatedAt()), str(j.startedAt()), str(j.finishedAt()), j.version());
        }

        private static Map<String, String> actor(ImportJob.Actor a) {
            if (a == null) return null;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("type", a.type());
            m.put("id", a.id());
            return m;
        }

        private static String str(Object o) {
            return o == null ? null : o.toString();
        }
    }

    record RowView(long row, int line, String id, Map<String, Object> validation, Map<String, Object> apply) { }

    private final ImportJobService jobs;

    ImportJobController(ImportJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    ResponseEntity<JobView> create(@RequestBody CreateJobRequest body, HttpServletRequest request) {
        ImportJob job = jobs.create(body == null ? null : body.kind(), body == null ? null : body.note(), AdminActors.require(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(JobView.of(job));
    }

    @PostMapping(path = "/{id}/rows", consumes = "text/csv")
    ImportJobService.Appended appendCsv(@PathVariable String id, HttpServletRequest request) throws IOException {
        AdminActors.require(request);
        try {
            return jobs.appendCsv(id, request.getInputStream());
        } catch (ImportCsvParser.ImportFileException e) {
            throw ImportJobException.invalid("the file could not be read: " + e.getMessage());
        }
    }

    @PostMapping(path = "/{id}/rows", consumes = MediaType.APPLICATION_JSON_VALUE)
    ImportJobService.Appended appendJson(@PathVariable String id, @RequestBody RowsRequest body, HttpServletRequest request) {
        AdminActors.require(request);
        return jobs.appendRows(id, body == null ? null : body.rows());
    }

    @PutMapping("/{id}/rows/{row}")
    JobView correct(@PathVariable String id, @PathVariable long row, @RequestBody CreateProductRequest body, HttpServletRequest request) {
        return JobView.of(jobs.correctRow(id, row, body, AdminActors.require(request)));
    }

    @PostMapping("/{id}/validate")
    JobView validate(@PathVariable String id, @RequestBody(required = false) VersionedRequest body, HttpServletRequest request) {
        return JobView.of(jobs.validate(id, version(body), AdminActors.require(request)));
    }

    @PostMapping("/{id}/apply")
    JobView apply(@PathVariable String id, @RequestBody(required = false) VersionedRequest body, HttpServletRequest request) {
        return JobView.of(jobs.apply(id, version(body), AdminActors.require(request)));
    }

    @PostMapping("/{id}/resume")
    JobView resume(@PathVariable String id, @RequestBody(required = false) VersionedRequest body, HttpServletRequest request) {
        return JobView.of(jobs.resume(id, version(body), AdminActors.require(request)));
    }

    @PostMapping("/{id}/cancel")
    JobView cancel(@PathVariable String id, @RequestBody(required = false) VersionedRequest body, HttpServletRequest request) {
        return JobView.of(jobs.cancel(id, version(body), AdminActors.require(request)));
    }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String status, @RequestParam(required = false) String after,
                             @RequestParam(defaultValue = "50") int limit) {
        List<JobView> out = new ArrayList<>();
        for (ImportJob j : jobs.list(status, after, limit)) out.add(JobView.of(j));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobs", out);
        m.put("next", out.isEmpty() ? null : out.get(out.size() - 1).id());
        return m;
    }

    @GetMapping("/{id}")
    JobView get(@PathVariable String id) {
        return JobView.of(jobs.require(id));
    }

    @GetMapping("/{id}/rows")
    Map<String, Object> rows(@PathVariable String id, @RequestParam(defaultValue = "0") long from, @RequestParam(defaultValue = "100") int limit) {
        List<RowView> out = new ArrayList<>();
        for (Document r : jobs.rows(id, from, limit)) {
            Document payload = r.get("payload", Document.class);
            out.add(new RowView(r.getLong("row"), r.getInteger("line", 0), payload == null ? null : payload.getString("id"),
                    r.get("validation", Document.class), r.get("apply", Document.class)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", out);
        m.put("next", out.isEmpty() ? null : out.get(out.size() - 1).row() + 1);
        return m;
    }

    @GetMapping(path = "/{id}/errors.csv", produces = "text/csv")
    void errors(@PathVariable String id, HttpServletResponse response) throws IOException {
        jobs.require(id);
        response.setContentType("text/csv; charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + id + "-errors.csv\"");
        PrintWriter w = response.getWriter();
        jobs.writeErrorsCsv(id, w);
        w.flush();
    }

    private static Long version(VersionedRequest body) {
        return body == null ? null : body.version();
    }
}
