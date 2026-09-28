package com.liskovsoft.youtubeapi.videoinfo.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    /** The verdict every client gave the ended stream jfKfPfyJRdk, in the locales we name. */
    @Test
    public void aStreamWhoseRecordingIsGoneIsTheSameVerdictEverywhere() {
        String spanish = BotCheckDetector.definitiveUnplayableKey("UNPLAYABLE",
                "La grabación de esta emisión en directo no está disponible.");
        assertNotNull(spanish);
        assertEquals("accents and spacing must not matter", spanish,
                BotCheckDetector.definitiveUnplayableKey("UNPLAYABLE",
                        "  La grabacion de esta  emision en directo no esta disponible. "));
        for (String reason : new String[] {
                "This live stream recording is not available.",
                "This live stream recording is not available",
                "\u201cThis live stream recording is not available.\u201d",
                "L'enregistrement de cette diffusion en direct n'est pas disponible.",
                "L\u2019enregistrement de cette diffusion en direct n\u2019est pas disponible.",
                "Die Aufzeichnung dieses Livestreams ist nicht verfügbar.",
                "La registrazione di questo live streaming non è disponibile.",
                "A gravação desta transmissão ao vivo não está disponível."}) {
            assertNotNull(reason, BotCheckDetector.definitiveUnplayableKey("UNPLAYABLE", reason));
        }
    }

    /** The other allowlisted terminal verdicts; removals arrive as ERROR as well. */
    @Test
    public void removalsAndTerminationsAreTerminal() {
        for (String reason : new String[] {
                "This video has been removed by the uploader",
                "This video is no longer available because the YouTube account associated with"
                        + " this video has been terminated.",
                "This video has been removed for violating YouTube's Terms of Service",
                "This video has been removed for violating YouTube\u2019s Community Guidelines.",
                "Dieses Video wurde entfernt, weil es gegen die Nutzungsbedingungen von YouTube"
                        + " verst\u00f6\u00dft."}) {
            assertNotNull(reason, BotCheckDetector.definitiveUnplayableKey("ERROR", reason));
            assertNotNull(reason, BotCheckDetector.definitiveUnplayableKey("UNPLAYABLE", reason));
        }
    }

    /**
     * 6SJNVb0GnPI on the Pixel (2026-09-28): seven clients named the policy and added a "learn
     * more ... in your country" sentence, WEB_EMBED named the terms of service. One verdict.
     */
    @Test
    public void aRemovalForAViolationIsOneVerdictWhateverThePolicyOrTheClient() {
        String app = BotCheckDetector.definitiveUnplayableKey("ERROR", "Este vídeo se ha retirado"
                + " por infringir la política de YouTube sobre la incitación al odio. Obtén más"
                + " información sobre cómo combatir la incitación al odio en tu país.");
        assertNotNull(app);
        assertEquals(app, BotCheckDetector.definitiveUnplayableKey("ERROR",
                "Este vídeo se ha retirado porque infringía los Términos del Servicio de YouTube"));
        for (String reason : new String[] {
                "This video has been removed for violating YouTube's policy on hate speech. Learn"
                        + " more about combating hate speech in your country.",
                "This video has been removed for violating YouTube's Terms of Service",
                "This video has been removed for violating YouTube\u2019s Community Guidelines.",
                "Dieses Video wurde entfernt, weil es gegen die Nutzungsbedingungen von YouTube"
                        + " verst\u00f6\u00dft."}) {
            assertEquals(reason, app, BotCheckDetector.definitiveUnplayableKey("ERROR", reason));
        }
        assertNotEquals("a removal by the uploader is another verdict", app,
                BotCheckDetector.definitiveUnplayableKey("ERROR",
                        "This video has been removed by the uploader"));
        assertNull("a region in the verdict's own sentence still vetoes it",
                BotCheckDetector.definitiveUnplayableKey("ERROR",
                        "This video has been removed for violating YouTube's policy in your"
                                + " country"));
    }

    /** Codex sol's counterexamples (2026-09-29): a qualified removal is not the content's verdict. */
    @Test
    public void aRemovalQualifiedByADeviceASiteOrAnyTailOtherThanLearnMoreIsNotAVerdict() {
        for (String reason : new String[] {
                "This video has been removed for violating YouTube's embedding policy on this"
                        + " website",
                "This video has been removed for violating YouTube's policy on this device.",
                "Este vídeo se ha retirado por infringir la política de YouTube en esta aplicación.",
                "This video has been removed for violating YouTube's local-law policy. This"
                        + " restriction applies only in your country.",
                "This video has been removed for violating YouTube's policy in the U.S. only.",
                "This video has been removed for violating YouTube's Terms of Service. Sign in to"
                        + " confirm your age."}) {
            assertNull(reason, BotCheckDetector.definitiveUnplayableKey("ERROR", reason));
        }
    }

    /**
     * The WHOLE reason must be an allowlisted sentence: a qualified, extended or reworded variant
     * is not the same verdict, and a copyright claim names a claimant, so it is never one.
     */
    @Test
    public void aQualifiedOrRewordedVerdictDoesNotMatch() {
        for (String reason : new String[] {
                "This live stream recording is not available on this device.",
                "La grabación de esta emisión en directo no está disponible en este dispositivo.",
                "This live stream recording is not available. Learn more",
                "La grabación de esta emisión en directo no está disponible • Más información",
                "This video has been removed by the uploader for now",
                "Sorry, this live stream recording is not available.",
                "This video is no longer available due to a copyright claim by Some Label"}) {
            assertNull(reason, BotCheckDetector.definitiveUnplayableKey("UNPLAYABLE", reason));
            assertNull(reason, BotCheckDetector.definitiveUnplayableKey("ERROR", reason));
        }
    }

    /**
     * Stability over speed: a generic or client-specific reason never qualifies, whatever the
     * clients agree on - the walk must go on to the client that might serve it.
     */
    @Test
    public void genericOrClientSpecificVerdictsAreNeverDefinitive() {
        for (String reason : new String[] {
                "Video unavailable", "Vídeo no disponible", "Este vídeo no está disponible",
                "This video is not available", "This video is unavailable",
                "The uploader has not made this video available in your country",
                "Este vídeo no está disponible en tu país",
                "This video contains content from Some Label, who has blocked it in your country"
                        + " on copyright grounds",
                "Sign in to confirm your age",
                "This video is available to this channel's members on level: Fan",
                "Playback on other websites has been disabled by the video owner",
                "Sign in to confirm you're not a bot",
                "The page needs to be reloaded.",
                "", "   "}) {
            assertNull(reason, BotCheckDetector.definitiveUnplayableKey("UNPLAYABLE", reason));
            assertNull(reason, BotCheckDetector.definitiveUnplayableKey("ERROR", reason));
        }
        assertNull(BotCheckDetector.definitiveUnplayableKey("UNPLAYABLE", null));
        for (String status : new String[] {"LOGIN_REQUIRED", "AGE_CHECK_REQUIRED",
                "CONTENT_CHECK_REQUIRED", "LIVE_STREAM_OFFLINE", "OK", null}) {
            assertNull(status, BotCheckDetector.definitiveUnplayableKey(status,
                    "This live stream recording is not available."));
        }
    }
}
