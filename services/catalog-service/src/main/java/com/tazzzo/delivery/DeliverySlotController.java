package com.tazzzo.delivery;

import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.serviceability.PublicServiceability;
import com.tazzzo.serviceability.ServiceabilityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@code GET /v1/customer/delivery/slots?pin=&days=} — the delivery windows a signed-in customer can choose for a PIN.
 * Customer-authenticated ({@code CustomerAuthFilter} has verified the session); the answer is computed fresh and never
 * cached. Capacity is shown only as a status (AVAILABLE / FULL / CLOSED), never as a count. The service area comes from
 * the serviceability domain; an unserviceable PIN yields {@code serviceable:false} and no slots.
 */
@RestController
public class DeliverySlotController {

    public static final String PATH = "/v1/customer/delivery/slots";

    record SlotDto(String slotId, String date, String startsAt, String endsAt, String label, String status) { }

    record SlotsResponse(boolean serviceable, String timezone, List<SlotDto> slots, String requestId) { }

    private final DeliverySlotService slots;
    private final ServiceabilityService serviceability;

    public DeliverySlotController(DeliverySlotService slots, ServiceabilityService serviceability) {
        this.slots = slots;
        this.serviceability = serviceability;
    }

    @GetMapping(PATH)
    public ResponseEntity<SlotsResponse> list(@RequestParam(name = "pin", required = false) String pin,
                                              @RequestParam(name = "days", required = false) String days,
                                              HttpServletRequest request) {
        CustomerPrincipalResolver.require(request);
        if (pin == null || !Pincode.isValid(pin.trim())) {
            throw new DeliverySlotFailure(DeliverySlotFailure.Reason.INVALID_REQUEST);
        }
        int n = parseDays(days);
        PublicServiceability area = serviceability.resolvePublic(new Pincode(pin.trim()));
        List<SlotDto> out = List.of();
        if (area.serviceable()) {
            out = slots.availability(area.serviceAreaId(), n).stream()
                    .map(o -> new SlotDto(o.slotId(), o.date().toString(),
                            o.startsAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                            o.endsAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), o.label(), o.status().name()))
                    .toList();
        }
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SlotsResponse(area.serviceable(), slots.zone().getId(), out, requestId == null ? "unknown" : requestId.toString()));
    }

    private int parseDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return slots.horizonDays();
        }
        if (!raw.matches("[1-9][0-9]?")) {
            throw new DeliverySlotFailure(DeliverySlotFailure.Reason.INVALID_REQUEST);
        }
        int n = Integer.parseInt(raw);
        if (n > slots.horizonDays()) {
            throw new DeliverySlotFailure(DeliverySlotFailure.Reason.INVALID_REQUEST);
        }
        return n;
    }
}
