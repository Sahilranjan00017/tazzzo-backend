package com.tazzzo.serviceability.admin;

import com.tazzzo.serviceability.admin.ServiceAreaAdminDtos.AreaResponse;
import com.tazzzo.serviceability.admin.ServiceAreaAdminDtos.PageResponse;
import com.tazzzo.serviceability.admin.ServiceAreaAdminDtos.RouteDto;
import com.tazzzo.serviceability.admin.ServiceAreaAdminDtos.UpsertRequest;
import com.tazzzo.serviceability.admin.ServiceAreaAdminDtos.VersionRequest;
import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.serviceability.InvalidServiceabilityException;
import com.tazzzo.serviceability.ServiceArea;
import com.tazzzo.serviceability.ServiceabilityNotFoundException;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin transport for the serviceability configuration (one document per PIN). INTERNAL surface: {@code ApiAuthFilter}
 * has authenticated the caller and refused writes from the read-only roles. Contains no routing rules: every invariant
 * (route uniqueness, priority, CAS, audit-before-state) lives in {@link ServiceabilityService}; the audit actor comes
 * only from the authenticated principal ({@link AdminActors}), never from the body.
 */
@RestController
@RequestMapping("/api/v1/admin/service-areas")
public class ServiceAreaAdminController {

    static final String SOURCE = "admin-api";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 199;   // the service allows 200 rows; one extra row detects a next page

    private final ServiceabilityService service;

    public ServiceAreaAdminController(ServiceabilityService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse list(@RequestParam(name = "limit", required = false) Integer limit,
                             @RequestParam(name = "after", required = false) String after) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw new InvalidServiceabilityException("limit must be between 1 and " + MAX_LIMIT);
        }
        // fetch one extra row to know whether a next page exists without a count
        List<ServiceArea> rows = service.list(after, n + 1);
        boolean more = rows.size() > n;
        List<ServiceArea> page = more ? rows.subList(0, n) : rows;
        return new PageResponse(page.stream().map(ServiceAreaAdminController::view).toList(),
                more ? page.get(page.size() - 1).pincode() : null);
    }

    @GetMapping("/{pincode}")
    public AreaResponse get(@PathVariable("pincode") String pincode) {
        return view(service.find(pin(pincode)).orElseThrow(() -> new ServiceabilityNotFoundException("no service area for pin")));
    }

    @PutMapping("/{pincode}")
    public ResponseEntity<AreaResponse> upsert(@PathVariable("pincode") String pincode, @RequestBody UpsertRequest body,
                                               HttpServletRequest request) {
        Pincode pin = pin(pincode);
        if (body == null || body.routes() == null) {
            throw new InvalidServiceabilityException("serviceAreaId and routes are required");
        }
        List<ServiceabilityRoute> routes = body.routes().stream().map(r -> {
            if (r == null || r.priority() == null || r.active() == null) {
                throw new InvalidServiceabilityException("every route needs fulfillmentLocationId, priority and active");
            }
            try {
                return new ServiceabilityRoute(r.fulfillmentLocationId(), r.priority(), r.active());
            } catch (IllegalArgumentException e) {
                throw new InvalidServiceabilityException(e.getMessage());
            }
        }).toList();
        service.upsertServiceArea(AdminActors.require(request),
                new UpsertServiceAreaCommand(pin.value(), body.serviceAreaId(), routes, SOURCE, body.expectedVersion()));
        return ResponseEntity.status(body.expectedVersion() == null ? HttpStatus.CREATED : HttpStatus.OK)
                .body(get(pin.value()));
    }

    @PostMapping("/{pincode}/activate")
    public AreaResponse activate(@PathVariable("pincode") String pincode, @RequestBody VersionRequest body,
                                 HttpServletRequest request) {
        return setActive(pincode, body, request, true);
    }

    @PostMapping("/{pincode}/deactivate")
    public AreaResponse deactivate(@PathVariable("pincode") String pincode, @RequestBody VersionRequest body,
                                   HttpServletRequest request) {
        return setActive(pincode, body, request, false);
    }

    private AreaResponse setActive(String pincode, VersionRequest body, HttpServletRequest request, boolean active) {
        Pincode pin = pin(pincode);
        if (body == null || body.expectedVersion() == null) {
            throw new InvalidServiceabilityException("expectedVersion is required");
        }
        service.setActive(AdminActors.require(request), pin, body.expectedVersion(), active);
        return get(pin.value());
    }

    private static Pincode pin(String raw) {
        if (!Pincode.isValid(raw)) {
            throw new InvalidServiceabilityException("invalid pincode");
        }
        return new Pincode(raw);
    }

    private static AreaResponse view(ServiceArea a) {
        return new AreaResponse(a.pincode(), a.serviceAreaId(), a.active(), a.version(),
                a.routes().stream().map(r -> new RouteDto(r.fulfillmentLocationId(), r.priority(), r.active())).toList());
    }
}
