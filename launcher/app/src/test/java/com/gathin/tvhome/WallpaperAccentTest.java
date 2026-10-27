package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class WallpaperAccentTest {
    @Test public void aFewVividPixelsBeatALotOfGrey() {
        int[] px = new int[1000];
        Arrays.fill(px, 0xFFA79D94);                 // the warm grey ground
        for (int i = 0; i < 40; i++) px[i] = 0xFFE8573F;   // a few red petals
        assertEquals(0xFFE8573F, WallpaperAccent.pick(px, 0xFFFFFFFF));
    }

    @Test public void aGreyPictureKeepsTheFallback() {
        int[] px = new int[100];
        Arrays.fill(px, 0xFF808080);
        assertEquals(0xFF123456, WallpaperAccent.pick(px, 0xFF123456));
    }
}
