package com.tazzzo.commerce.contract;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PincodeTest {

    @Test void valid_pin() {
        assertEquals("560047", new Pincode("560047").value());
        assertTrue(Pincode.isValid("110001"));
    }

    @Test void too_short_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new Pincode("56004"));
    }

    @Test void too_long_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new Pincode("5600477"));
    }

    @Test void alphabetic_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new Pincode("5600AB"));
    }

    @Test void whitespace_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new Pincode(" 560047"));
        assertThrows(IllegalArgumentException.class, () -> new Pincode("560047 "));
    }

    @Test void null_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new Pincode(null));
        assertFalse(Pincode.isValid(null));
    }

    @Test void leading_zero_rejected_not_a_valid_indian_pin() {
        // Indian PINs never start with 0.
        assertThrows(IllegalArgumentException.class, () -> new Pincode("060047"));
        assertFalse(Pincode.isValid("012345"));
    }
}
