package com.tazzzo.commerce.read;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pacing formula: rows per pass so a full pass over the catalogue fits the target, floored and capped. */
class ProjectionReconcilePacingTest {

    static final long FIVE_MIN = 300_000;
    static final long FOUR_H = 14_400_000;
    static final long DAY = 86_400_000;

    @Test
    void small_catalogues_keep_the_historical_floor() {
        assertThat(ProjectionReconciler.pacedLimit(0, FIVE_MIN, FOUR_H, 500, 20_000)).isEqualTo(500);
        assertThat(ProjectionReconciler.pacedLimit(5_000, FIVE_MIN, FOUR_H, 500, 20_000)).isEqualTo(500);   // needs 105
        assertThat(ProjectionReconciler.pacedLimit(24_000, FIVE_MIN, FOUR_H, 500, 20_000)).isEqualTo(500);  // needs exactly 500
    }

    @Test
    void the_limit_grows_with_the_catalogue_so_the_full_pass_target_holds() {
        assertThat(ProjectionReconciler.pacedLimit(25_000, FIVE_MIN, FOUR_H, 500, 20_000)).isEqualTo(521);
        assertThat(ProjectionReconciler.pacedLimit(100_000, FIVE_MIN, FOUR_H, 500, 20_000)).isEqualTo(2_084);
        // a full pass at that limit: ceil(100000 / 2084) = 48 passes x 5 min = 4 h
        assertThat((long) Math.ceil(100_000.0 / 2_084) * FIVE_MIN).isLessThanOrEqualTo(FOUR_H);
        assertThat(ProjectionReconciler.pacedLimit(100_000, FIVE_MIN, DAY, 500, 20_000)).isEqualTo(500);     // 24 h target: floor suffices (16.7 h)
        assertThat(ProjectionReconciler.pacedLimit(1_000_000, FIVE_MIN, FOUR_H, 500, 20_000)).isEqualTo(20_000); // needs 20,834: capped
        assertThat(ProjectionReconciler.pacedLimit(1_000_000, FIVE_MIN, FOUR_H, 500, 50_000)).isEqualTo(20_834);
    }

    @Test
    void the_ceiling_is_exact_and_bounded() {
        assertThat(ProjectionReconciler.pacedLimit(1, FIVE_MIN, FOUR_H, 1, 10)).isEqualTo(1);
        assertThat(ProjectionReconciler.pacedLimit(48, FIVE_MIN, FOUR_H, 1, 10)).isEqualTo(1);     // 48 * 1/48 = 1 exactly
        assertThat(ProjectionReconciler.pacedLimit(49, FIVE_MIN, FOUR_H, 1, 10)).isEqualTo(2);     // just over: rounds up
        assertThat(ProjectionReconciler.pacedLimit(Long.MAX_VALUE / 1_000_000, FIVE_MIN, FOUR_H, 1, 10)).isEqualTo(10);
    }

    @Test
    void misconfiguration_is_refused() {
        assertThatThrownBy(() -> ProjectionReconciler.pacedLimit(10, 0, FOUR_H, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectionReconciler.pacedLimit(10, FIVE_MIN, 0, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectionReconciler.pacedLimit(10, FIVE_MIN, FOUR_H, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectionReconciler.pacedLimit(10, FIVE_MIN, FOUR_H, 10, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void count_times_interval_overflow_saturates_at_the_ceiling_instead_of_wrapping() {
        assertThat(ProjectionReconciler.pacedLimit(Long.MAX_VALUE, FIVE_MIN, FOUR_H, 500, 20_000)).isEqualTo(20_000);
        assertThat(ProjectionReconciler.pacedLimit(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 1, 500, 20_000)).isEqualTo(20_000);
    }
}
