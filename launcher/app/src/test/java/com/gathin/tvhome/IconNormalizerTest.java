package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IconNormalizerTest {
    private static int[] filled(int size, int colour) {
        int[] px = new int[size * size];
        Arrays.fill(px, colour);
        return px;
    }

    /** A logo in the middle half of a transparent square. */
    private static int[] logo(int size, int colour) {
        int[] px = new int[size * size];
        for (int y = size / 4; y < 3 * size / 4; y++) for (int x = size / 4; x < 3 * size / 4; x++) px[y * size + x] = colour;
        return px;
    }

    @Test public void aSquareIconFillsItsTile() {
        assertTrue(IconNormalizer.isFullBleed(filled(100, 0xFFE50914), 100, 100));
    }

    @Test public void aLogoOnTransparencyIsNotFullBleed() {
        assertFalse(IconNormalizer.isFullBleed(logo(100, 0xFFE50914), 100, 100));
    }

    @Test public void aWhiteLogoAsksForADarkTile() {
        assertTrue(IconNormalizer.contentIsLight(logo(100, 0xFFFFFFFF)));
        assertFalse(IconNormalizer.contentIsLight(logo(100, 0xFFE50914)));
    }
}
