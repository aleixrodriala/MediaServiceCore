package com.liskovsoft.youtubeapi.videoinfo.models;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BotCheckDetectorTest {
    @Test
    public void recognizesEnglishAndSpanishBotChecks() {
        assertTrue(BotCheckDetector.isExplicitBotCheck(
                "LOGIN_REQUIRED", "Sign in to confirm you're not a bot"));
        assertTrue(BotCheckDetector.isExplicitBotCheck(
                "LOGIN_REQUIRED", "Inicia sesión para confirmar que no eres un bot"));
    }

    @Test
    public void doesNotConfuseAgeGateWithBotCheck() {
        assertFalse(BotCheckDetector.isExplicitBotCheck(
                "LOGIN_REQUIRED", "Sign in to confirm your age"));
        assertFalse(BotCheckDetector.isExplicitBotCheck(
                "UNPLAYABLE", "Sign in to confirm you're not a bot"));
    }

    @Test
    public void repeatedLocalizedLoginReasonTripsFallbackDetection() {
        assertTrue(BotCheckDetector.isRepeatedLoginRequired(
                "LOGIN_REQUIRED", "Confirme que es una persona",
                "LOGIN_REQUIRED", "  Confirme   que es una persona "));
        assertFalse(BotCheckDetector.isRepeatedLoginRequired(
                "LOGIN_REQUIRED", "Sign in to confirm your age",
                "UNPLAYABLE", "Embedding disabled"));
    }
}
