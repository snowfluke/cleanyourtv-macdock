package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WeatherIconTest {
    @Test public void eachWmoGroupGetsItsIcon() {
        assertEquals("☀️", WeatherIcon.of(0, true));          // clear, day
        assertEquals("🌙", WeatherIcon.of(0, false));         // clear, night
        assertEquals("☁️", WeatherIcon.of(3, true));          // overcast
        assertEquals("🌫️", WeatherIcon.of(45, true));   // fog
        assertEquals("🌧️", WeatherIcon.of(61, true));   // rain
        assertEquals("🌧️", WeatherIcon.of(80, true));   // showers
        assertEquals("🌨️", WeatherIcon.of(73, true));   // snow
        assertEquals("⛈️", WeatherIcon.of(95, true));         // thunderstorm
    }
}
