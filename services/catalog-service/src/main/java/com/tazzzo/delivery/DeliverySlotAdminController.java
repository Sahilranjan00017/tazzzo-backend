package com.tazzzo.delivery;

import com.tazzzo.catalog.api.AdminActors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * INTERNAL admin transport for delivery windows. {@code ApiAuthFilter} has authenticated the caller and refused writes
 * from read-only roles; every rule (validation, CAS, area existence, audit with the authenticated actor) lives in
 * {@link DeliverySlotService}.
 */
@RestController
@RequestMapping("/api/v1/admin/delivery-slots/{serviceAreaId}")
public class DeliverySlotAdminController {

    record WindowRequest(String label, Integer startMinute, Integer endMinute, Integer cutoffMinutes, Integer capacity,
                         Set<Integer> days, Long expectedVersion) { }

    @io.swagger.v3.oas.annotations.media.Schema(name = "DeliveryWindowVersionRequest")
    record VersionRequest(Long expectedVersion) { }

    record WindowResponse(String serviceAreaId, String windowId, String label, int startMinute, int endMinute,
                          int cutoffMinutes, int capacity, Set<Integer> days, boolean active, long version) { }

    record ListResponse(List<WindowResponse> items) { }

    private final DeliverySlotService service;

    public DeliverySlotAdminController(DeliverySlotService service) {
        this.service = service;
    }

    @GetMapping
    public ListResponse list(@PathVariable("serviceAreaId") String serviceAreaId) {
        return new ListResponse(service.list(serviceAreaId).stream().map(DeliverySlotAdminController::view).toList());
    }

    @GetMapping("/{windowId}")
    public WindowResponse get(@PathVariable("serviceAreaId") String serviceAreaId, @PathVariable("windowId") String windowId) {
        return view(service.find(serviceAreaId, windowId).orElseThrow(() -> new DeliverySlotException.NotFound("no such window")));
    }

    @PutMapping("/{windowId}")
    public ResponseEntity<WindowResponse> upsert(@PathVariable("serviceAreaId") String serviceAreaId,
                                                 @PathVariable("windowId") String windowId,
                                                 @RequestBody WindowRequest body, HttpServletRequest request) {
        if (body == null || body.startMinute() == null || body.endMinute() == null || body.cutoffMinutes() == null
                || body.capacity() == null || body.days() == null) {
            throw new DeliverySlotException.Invalid("label, startMinute, endMinute, cutoffMinutes, capacity and days are required");
        }
        SlotWindow window;
        try {
            window = new SlotWindow(windowId, body.label(), body.startMinute(), body.endMinute(), body.cutoffMinutes(),
                    body.capacity(), body.days());
        } catch (IllegalArgumentException e) {
            throw new DeliverySlotException.Invalid(e.getMessage());
        }
        service.upsertWindow(AdminActors.require(request), serviceAreaId, window, body.expectedVersion());
        return ResponseEntity.status(body.expectedVersion() == null ? HttpStatus.CREATED : HttpStatus.OK)
                .body(get(serviceAreaId, windowId));
    }

    @PostMapping("/{windowId}/activate")
    public WindowResponse activate(@PathVariable("serviceAreaId") String serviceAreaId,
                                   @PathVariable("windowId") String windowId, @RequestBody VersionRequest body,
                                   HttpServletRequest request) {
        return setActive(serviceAreaId, windowId, body, request, true);
    }

    @PostMapping("/{windowId}/deactivate")
    public WindowResponse deactivate(@PathVariable("serviceAreaId") String serviceAreaId,
                                     @PathVariable("windowId") String windowId, @RequestBody VersionRequest body,
                                     HttpServletRequest request) {
        return setActive(serviceAreaId, windowId, body, request, false);
    }

    private WindowResponse setActive(String serviceAreaId, String windowId, VersionRequest body, HttpServletRequest request,
                                     boolean active) {
        if (body == null || body.expectedVersion() == null) {
            throw new DeliverySlotException.Invalid("expectedVersion is required");
        }
        service.setActive(AdminActors.require(request), serviceAreaId, windowId, body.expectedVersion(), active);
        return get(serviceAreaId, windowId);
    }

    private static WindowResponse view(DeliveryWindow w) {
        SlotWindow s = w.window();
        return new WindowResponse(w.serviceAreaId(), s.windowId(), s.label(), s.startMinute(), s.endMinute(),
                s.cutoffMinutes(), s.capacity(), s.days(), w.active(), w.version());
    }
}
