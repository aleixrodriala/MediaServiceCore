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

    /**
     * The verdict the authenticated TV route returned on every video on 2026-09-07. Diagnostic
     * only - routing keys off the response SHAPE - but it has to name both the locale the device
     * actually runs in and the one upstream reports in.
     */
    @Test
    public void recognizesTheReloadPageVerdictInBothObservedLocales() {
        assertTrue(BotCheckDetector.isReloadPageVerdict(
                "UNPLAYABLE", "The page needs to be reloaded."));
        assertTrue("the exact string captured on the Pixel 9",
                BotCheckDetector.isReloadPageVerdict(
                        "UNPLAYABLE", "Es necesario volver a cargar la página."));
        assertTrue("accents and spacing must not matter",
                BotCheckDetector.isReloadPageVerdict(
                        "UNPLAYABLE", "  ES  NECESARIO volver a cargar la pagina  "));
    }

    @Test
    public void reloadPageVerdictIsScopedToUnplayable() {
        assertFalse(BotCheckDetector.isReloadPageVerdict(
                "LOGIN_REQUIRED", "The page needs to be reloaded."));
        assertFalse(BotCheckDetector.isReloadPageVerdict("UNPLAYABLE", null));
        assertFalse(BotCheckDetector.isReloadPageVerdict(
                "UNPLAYABLE", "This video is private"));
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
