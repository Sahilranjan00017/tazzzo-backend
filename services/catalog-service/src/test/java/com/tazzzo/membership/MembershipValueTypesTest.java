package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-16A-1 -- identity, reference, plan and grant-construction rules. */
class MembershipValueTypesTest {

    static final MembershipPlan PLAN = new MembershipPlan("TAZZZO_PLUS_MONTHLY", 1, Money.ofInrPaise(9900), 1,
            Instant.parse("2026-01-01T00:00:00Z"), null);

    @Test
    void membership_ids_are_opaque_random_and_shape_checked() {
        MembershipId a = MembershipId.generate();
        assertThat(a.value()).startsWith("MBR_");
        assertThat(MembershipId.generate()).isNotEqualTo(a);
        assertThat(MembershipId.isValid(a.value())).isTrue();
        for (String bad : new String[] {null, "", "MBR_", "MBR_ab", "ORD_abcdefgh", "mbr_abcdefgh", "MBR_abc def"}) {
            assertThat(MembershipId.isValid(bad)).as(String.valueOf(bad)).isFalse();
        }
        assertThatThrownBy(() -> new MembershipId("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grant_references_are_namespaced_case_sensitive_and_never_normalised() {
        MembershipGrantReference r = new MembershipGrantReference(GrantSource.INTERNAL_GRANT, "Ref.1:a_b-C");
        assertThat(r.reference()).isEqualTo("Ref.1:a_b-C");
        assertThat(r).isNotEqualTo(new MembershipGrantReference(GrantSource.INTERNAL_GRANT, "ref.1:a_b-c"));
        assertThat(new MembershipGrantReference(GrantSource.INTERNAL_GRANT, "x".repeat(128))).isNotNull();
        for (String bad : new String[] {null, "", "x".repeat(129), "has space", "slash/ed", "ünï", "tab\t"}) {
            assertThatThrownBy(() -> new MembershipGrantReference(GrantSource.INTERNAL_GRANT, bad))
                    .as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MembershipGrantReference(null, "ok")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_grant_source_namespace_currently_holds_exactly_the_internal_producer() {
        assertThat(GrantSource.values()).containsExactly(GrantSource.INTERNAL_GRANT);
        assertThat(MembershipStatus.values()).containsExactly(MembershipStatus.ACTIVE, MembershipStatus.EXPIRED,
                MembershipStatus.REVOKED);
    }

    @Test
    void plan_money_is_int64_paise_and_the_plan_window_is_half_open() {
        assertThat(PLAN.price().paise()).isEqualTo(9900L);
        MembershipPlan bounded = new MembershipPlan("TAZZZO_PLUS_MONTHLY", 1, Money.ofInrPaise(9900), 1,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(bounded.isEffectiveAt(Instant.parse("2025-12-31T23:59:59.999Z"))).isFalse();
        assertThat(bounded.isEffectiveAt(Instant.parse("2026-01-01T00:00:00Z"))).isTrue();
        assertThat(bounded.isEffectiveAt(Instant.parse("2026-12-31T23:59:59.999Z"))).isTrue();
        assertThat(bounded.isEffectiveAt(Instant.parse("2027-01-01T00:00:00Z"))).isFalse();
        assertThat(PLAN.isEffectiveAt(Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void invalid_plans_fail_loud() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> new MembershipPlan("bad", 1, Money.ofInrPaise(1), 1, from, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipPlan("ABC", 0, Money.ofInrPaise(1), 1, from, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipPlan("ABC", 1, Money.ofInrPaise(0), 1, from, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipPlan("ABC", 1, null, 1, from, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipPlan("ABC", 1, Money.ofInrPaise(1), 121, from, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipPlan("ABC", 1, Money.ofInrPaise(1), 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipPlan("ABC", 1, Money.ofInrPaise(1), 1, from, from))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_new_grant_is_ACTIVE_version_1_one_period_snapshotting_the_plan_in_the_billing_zone() {
        CustomerId customer = CustomerId.generate();
        MembershipId id = MembershipId.generate();
        MembershipGrantReference ref = new MembershipGrantReference(GrantSource.INTERNAL_GRANT, "G-1");
        Instant now = Instant.parse("2027-02-28T20:00:00.123456789Z");

        Membership m = Membership.newGrant(id, customer, ref, PLAN, now);

        assertThat(m.membershipId()).isEqualTo(id);
        assertThat(m.customerId()).isEqualTo(customer);
        assertThat(m.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(m.version()).isEqualTo(1L);
        assertThat(m.periodCount()).isEqualTo(1L);
        assertThat(m.grantReference().source()).isEqualTo(GrantSource.INTERNAL_GRANT);
        assertThat(m.billingZoneId()).isEqualTo("Asia/Kolkata");
        assertThat(m.planId()).isEqualTo("TAZZZO_PLUS_MONTHLY");
        assertThat(m.planVersion()).isEqualTo(1);
        assertThat(m.planPrice().paise()).isEqualTo(9900L);
        assertThat(m.planPeriodMonths()).isEqualTo(1);
        assertThat(m.validFrom()).isEqualTo(Instant.parse("2027-02-28T20:00:00.123Z"));
        assertThat(m.validUntil()).isEqualTo(Instant.parse("2027-03-31T20:00:00.123Z"));
        assertThat(m.createdAt()).isEqualTo(m.validFrom());
        assertThat(m.updatedAt()).isEqualTo(m.validFrom());
    }

    @Test
    void the_runtime_window_is_half_open_and_a_future_start_is_not_a_window_end() {
        Membership m = Membership.newGrant(MembershipId.generate(), CustomerId.generate(),
                new MembershipGrantReference(GrantSource.INTERNAL_GRANT, "G-2"), PLAN,
                Instant.parse("2027-01-31T04:30:00Z"));
        assertThat(m.validUntil()).isEqualTo(Instant.parse("2027-02-28T04:30:00Z"));
        assertThat(m.windowEndedAt(Instant.parse("2027-02-28T04:29:59.999Z"))).isFalse();
        assertThat(m.windowEndedAt(Instant.parse("2027-02-28T04:30:00Z"))).as("exclusive end").isTrue();
        // clock-skew anomaly: now < validFrom -> the window has NOT ended (it still holds the open slot)
        assertThat(m.windowEndedAt(Instant.parse("2027-01-31T04:29:59.999Z"))).isFalse();
    }
}
