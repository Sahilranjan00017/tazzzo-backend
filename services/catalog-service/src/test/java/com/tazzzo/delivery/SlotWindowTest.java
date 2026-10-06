package com.tazzzo.delivery;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotWindowTest {

    static SlotWindow ok() {
        return new SlotWindow("morning", "Morning", 480, 600, 60, 10, Set.of(1, 2, 3, 4, 5));
    }

    @Test
    void a_valid_window_is_accepted_and_days_are_immutable_and_ordered() {
        SlotWindow w = new SlotWindow("w-1", "Evening", 1080, 1260, 0, 1, Set.of(7, 1));
        assertThat(w.days()).containsExactly(1, 7);
        assertThatThrownBy(() -> w.days().add(3)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ids_labels_times_cutoff_capacity_and_days_are_bounded() {
        for (String id : new String[]{null, "", "Morning", "-x", "a b", "a|b", "a".repeat(33), "ä"}) {
            assertThatThrownBy(() -> new SlotWindow(id, "L", 0, 60, 0, 1, Set.of(1))).as("id " + id).isInstanceOf(IllegalArgumentException.class);
        }
        for (String label : new String[]{null, "", " ", " pad", "pad ", "x".repeat(61), "bad\nlabel"}) {
            assertThatThrownBy(() -> new SlotWindow("w", label, 0, 60, 0, 1, Set.of(1))).as("label " + label).isInstanceOf(IllegalArgumentException.class);
        }
        int[][] times = {{-1, 60}, {60, 60}, {90, 60}, {0, 1441}};
        for (int[] t : times) {
            assertThatThrownBy(() -> new SlotWindow("w", "L", t[0], t[1], 0, 1, Set.of(1))).as(t[0] + "-" + t[1]).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new SlotWindow("w", "L", 0, 1440, 0, 1, Set.of(1)).endMinute()).isEqualTo(1440);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, -1, 1, Set.of(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, 10081, 1, Set.of(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, 0, 0, Set.of(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, 0, SlotWindow.MAX_CAPACITY + 1, Set.of(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, 0, 1, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, 0, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, 0, 1, Set.of(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotWindow("w", "L", 0, 60, 0, 1, Set.of(8))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ok().capacity()).isEqualTo(10);
    }
}
