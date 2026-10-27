package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MacDockStepTest {
    @Test public void leftAndRightGoRoundAtBothEnds() {
        assertEquals(1, MacDock.step(0, 6, 1));
        assertEquals(0, MacDock.step(5, 6, 1));    // Right on the Apps button: back to the first app
        assertEquals(5, MacDock.step(0, 6, -1));   // Left on the first app: the Apps button
        assertEquals(3, MacDock.step(4, 6, -1));
    }

    @Test public void anEmptyDockStaysAtZero() {
        assertEquals(0, MacDock.step(0, 0, 1));
    }
}
