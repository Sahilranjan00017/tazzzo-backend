package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-16A-1 -- strict persisted-record reconstruction: no default, no shim, no normalisation. */
class MembershipReconstructionTest {

    private static final Instant FROM = Instant.parse("2027-01-31T04:30:00Z");

    private static Membership active() {
        return Membership.newGrant(MembershipId.generate(), CustomerId.generate(),
                new MembershipGrantReference(GrantSource.INTERNAL_GRANT, "R-1"), MembershipValueTypesTest.PLAN, FROM);
    }

    private static Document activeDoc() {
        return MembershipRepository.toDocument(active());
    }

    /** A well-formed EXPIRED document: version advanced, openTerm absent, persisted at the window end. */
    private static Document expiredDoc() {
        Document d = activeDoc();
        d.put("status", "EXPIRED");
        d.remove("openTerm");
        d.put("version", 2L);
        d.put("updatedAt", d.get("validUntil"));
        return d;
    }

    private static void assertCorrupt(Document d, String why) {
        assertThatThrownBy(() -> MembershipRepository.toMembership(d)).as(why)
                .isInstanceOfSatisfying(MembershipFailure.class,
                        e -> assertThat(e.reason()).isEqualTo(MembershipFailure.Reason.INTEGRITY_FAILURE));
    }

    private static void assertCorrupt(Consumer<Document> mutate, String why) {
        Document d = activeDoc();
        mutate.accept(d);
        assertCorrupt(d, why);
    }

    @Test
    void a_well_formed_ACTIVE_row_round_trips_exactly() {
        Membership m = active();
        Document d = MembershipRepository.toDocument(m);
        assertThat(d.get("openTerm")).isEqualTo(Boolean.TRUE);
        assertThat(d.getString("billingZoneId")).isEqualTo("Asia/Kolkata");
        assertThat(d.getString("grantSource")).isEqualTo("INTERNAL_GRANT");
        assertThat(d.get("validFrom")).isInstanceOf(Date.class);
        assertThat(MembershipRepository.toMembership(d)).isEqualTo(m);
    }

    @Test
    void a_well_formed_EXPIRED_row_has_no_openTerm_field_at_all() {
        Document d = expiredDoc();
        assertThat(d.containsKey("openTerm")).isFalse();
        assertThat(MembershipRepository.toMembership(d).status()).isEqualTo(MembershipStatus.EXPIRED);
    }

    @Test
    void every_missing_required_field_is_an_integrity_failure() {
        List<String> required = List.of("_id", "customerId", "status", "version", "grantSource", "grantRef", "planId",
                "planVersion", "planPricePaise", "planCurrency", "planPeriodMonths", "billingZoneId", "periodCount",
                "validFrom", "validUntil", "createdAt", "updatedAt");
        for (String field : required) {
            assertCorrupt(d -> d.remove(field), "missing " + field);
        }
    }

    @Test
    void a_missing_billing_zone_has_no_default_and_a_wrong_one_is_rejected() {
        assertCorrupt(d -> d.remove("billingZoneId"), "missing => Kolkata is exactly the forbidden shim");
        assertCorrupt(d -> d.put("billingZoneId", "UTC"), "UTC");
        assertCorrupt(d -> d.put("billingZoneId", "Asia/Calcutta"), "legacy alias");
        assertCorrupt(d -> d.put("billingZoneId", "asia/kolkata"), "case");
    }

    @Test
    void every_malformed_openTerm_encoding_is_rejected_and_never_normalised() {
        assertCorrupt(d -> d.remove("openTerm"), "ACTIVE without the marker");
        assertCorrupt(d -> d.put("openTerm", false), "ACTIVE with false");
        assertCorrupt(d -> d.put("openTerm", null), "ACTIVE with null");
        assertCorrupt(d -> d.put("openTerm", "true"), "string");
        assertCorrupt(d -> d.put("openTerm", 1), "number");
        assertCorrupt(d -> d.put("openTerm", new Document()), "document");
        for (Object leftover : new Object[] {Boolean.TRUE, Boolean.FALSE, null}) {
            Document expired = expiredDoc();
            expired.put("openTerm", leftover);
            assertCorrupt(expired, "terminal row still containing openTerm=" + leftover);
        }
    }

    @Test
    void a_validUntil_that_disagrees_with_the_billing_calendar_formula_is_rejected() {
        // 2027-03-01 01:30 IST = 2027-02-28T20:00Z: here the UTC date (28 Feb) differs from the IST date (1 Mar)
        Instant differingDate = Instant.parse("2027-02-28T20:00:00Z");
        Instant utcRule = ZonedDateTime.ofInstant(differingDate, ZoneOffset.UTC).plusMonths(1).toInstant();
        Document utcRuleRow = MembershipRepository.toDocument(Membership.newGrant(MembershipId.generate(),
                CustomerId.generate(), new MembershipGrantReference(GrantSource.INTERNAL_GRANT, "R-9"),
                MembershipValueTypesTest.PLAN, differingDate));
        assertThat(MembershipRepository.toMembership(utcRuleRow).validUntil())
                .isEqualTo(Instant.parse("2027-03-31T20:00:00Z"));
        utcRuleRow.put("validUntil", Date.from(utcRule));
        assertCorrupt(utcRuleRow, "the rejected UTC rule (" + utcRule + ")");
        assertCorrupt(d -> d.put("validUntil", Date.from(Instant.parse("2027-02-28T04:30:00.001Z"))), "off by 1ms");
        assertCorrupt(d -> d.put("validUntil", Date.from(FROM.plusSeconds(30L * 86400))), "fixed 30-day");
        assertCorrupt(d -> d.put("periodCount", 2L), "periodCount no longer matches validUntil");
        assertCorrupt(d -> d.put("planPeriodMonths", 2), "period months no longer match validUntil");
    }

    @Test
    void an_unknown_status_or_grant_source_fails_loud() {
        assertCorrupt(d -> d.put("status", "REVOKED"), "REVOKED does not exist in PR-16A-1");
        assertCorrupt(d -> d.put("status", "active"), "case");
        assertCorrupt(d -> d.put("grantSource", "PAYMENT"), "PAYMENT source does not exist yet");
        assertCorrupt(d -> d.put("grantSource", ""), "blank source");
    }

    @Test
    void invalid_money_is_rejected_and_never_coerced() {
        assertCorrupt(d -> d.put("planPricePaise", 0L), "zero price");
        assertCorrupt(d -> d.put("planPricePaise", -9900L), "negative price");
        assertCorrupt(d -> d.put("planPricePaise", 9900.0d), "a double is not paise");
        assertCorrupt(d -> d.put("planPricePaise", "9900"), "a string is not paise");
        assertCorrupt(d -> d.put("planCurrency", "USD"), "non-INR");
        assertCorrupt(d -> d.put("planCurrency", "inr"), "case");
    }

    @Test
    void malformed_identities_and_counters_are_rejected() {
        assertCorrupt(d -> d.put("_id", "not-an-id"), "membership id shape");
        assertCorrupt(d -> d.put("customerId", "not-a-customer"), "customer id shape");
        assertCorrupt(d -> d.put("grantRef", "has space"), "grant ref shape");
        assertCorrupt(d -> d.put("version", 0L), "version 0");
        assertCorrupt(d -> d.put("version", 1.0d), "double version");
        assertCorrupt(d -> d.put("planVersion", 0), "planVersion 0");
        assertCorrupt(d -> d.put("planVersion", 1L << 40), "planVersion beyond int");
        assertCorrupt(d -> d.put("periodCount", 0L), "periodCount 0");
        assertCorrupt(d -> d.put("planPeriodMonths", 0), "period months 0");
        assertCorrupt(d -> d.put("planPeriodMonths", 121), "period months 121");
        assertCorrupt(d -> d.put("planId", "bad id"), "plan id shape");
    }

    @Test
    void timestamp_types_and_ordering_are_enforced() {
        assertCorrupt(d -> d.put("validFrom", "2027-01-31T04:30:00Z"), "string date");
        assertCorrupt(d -> d.put("createdAt", null), "null date");
        assertCorrupt(d -> d.put("createdAt", Date.from(FROM.minusSeconds(1))), "createdAt != validFrom");
        assertCorrupt(d -> d.put("updatedAt", Date.from(FROM.minusSeconds(1))), "updatedAt before createdAt");
        assertCorrupt(d -> d.put("validFrom", d.get("validUntil")), "validFrom not before validUntil");
    }

    @Test
    void an_EXPIRED_row_must_have_transitioned_and_never_before_its_window_ended() {
        Document notTransitioned = expiredDoc();
        notTransitioned.put("version", 1L);
        assertCorrupt(notTransitioned, "EXPIRED at version 1");

        Document early = expiredDoc();
        early.put("updatedAt", Date.from(Instant.parse("2027-02-28T04:29:59.999Z")));
        assertCorrupt(early, "EXPIRED persisted before validUntil");
    }

    @Test
    void a_stale_ACTIVE_row_is_valid_and_not_corruption() {
        // persisted ACTIVE long after its window ended: valid stale state
        Membership stale = MembershipRepository.toMembership(activeDoc());
        assertThat(stale.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(stale.windowEndedAt(Instant.parse("2030-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void unknown_extra_fields_are_ignored_but_nothing_required_is_ever_defaulted() {
        Membership m = active();
        Document d = MembershipRepository.toDocument(m);
        d.put("someFutureField", "x");
        assertThat(MembershipRepository.toMembership(d)).isEqualTo(m);
    }
}
