package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoodTest {

    @Test
    void aBardAtTheTavernLiftsSpirits() {
        Mood.Inputs plain = new Mood.Inputs();
        Mood.Inputs music = new Mood.Inputs();
        music.entertained = true;
        Mood.Result a = Mood.target(plain), b = Mood.target(music);
        assertEquals(4, b.target() - a.target(), 1e-9);
        assertTrue(b.factors().containsKey("Music at the tavern"));
        assertFalse(a.factors().containsKey("Music at the tavern"));
    }
}
