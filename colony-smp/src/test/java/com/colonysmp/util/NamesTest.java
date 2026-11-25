package com.colonysmp.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamesTest {

    @Test
    void keepsAFreeName() {
        assertEquals("Ines Rowe", Names.unique("Ines Rowe", n -> false));
    }

    @Test
    void aTakenNameKeepsTheSurname() {
        Set<String> taken = Set.of("Ines Rowe");
        String n = Names.unique("Ines Rowe", taken::contains);
        assertFalse(taken.contains(n));
        assertTrue(n.endsWith(" Rowe"), n);
    }

    @Test
    void neverRepeatsInABigColony() {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            String n = Names.unique(Names.random(), used::contains);
            assertTrue(used.add(n), "repeated " + n);
        }
    }
}
