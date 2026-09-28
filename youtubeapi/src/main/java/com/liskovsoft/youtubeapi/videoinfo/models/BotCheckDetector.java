package com.liskovsoft.youtubeapi.videoinfo.models;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Locale;

/** Classifies YouTube anti-bot player responses without depending on the user's locale. */
public final class BotCheckDetector {
    private static final String STATUS_LOGIN_REQUIRED = "LOGIN_REQUIRED";
    private static final String STATUS_UNPLAYABLE = "UNPLAYABLE";
    /**
     * Localized forms of the "reload the page" verdict YouTube returns to authenticated TVHTML5
     * /player requests (see {@link #isReloadPageVerdict}). Normalized the same way as every other
     * reason here, so accents and whitespace do not matter.
     */
    private static final String[] RELOAD_PAGE_REASONS = {
            "the page needs to be reloaded",            // en
            "es necesario volver a cargar la pagina",   // es
            "la page doit etre rechargee",              // fr
            "die seite muss neu geladen werden",        // de
            "e necessario ricaricare la pagina",        // it
            "a pagina precisa ser recarregada",         // pt-BR
    };

    private BotCheckDetector() {
    }

    public static boolean isExplicitBotCheck(@Nullable String status, @Nullable String reason) {
        if (!STATUS_LOGIN_REQUIRED.equals(status) || reason == null) {
            return false;
        }

        String normalized = normalize(reason);
        return normalized.contains("bot")
                || normalized.contains("robot")
                || normalized.contains("captcha");
    }

    /**
     * A localized anti-bot message may not contain a Latin keyword. Two different clients
     * returning the same LOGIN_REQUIRED reason is the safe fallback signal: an age gate normally
     * changes outcome on the embedded client, while a blocked guest/IP session does not.
     */
    public static boolean isRepeatedLoginRequired(@Nullable String firstStatus,
            @Nullable String firstReason, @Nullable String currentStatus,
            @Nullable String currentReason) {
        if (!STATUS_LOGIN_REQUIRED.equals(firstStatus)
                || !STATUS_LOGIN_REQUIRED.equals(currentStatus)
                || firstReason == null || currentReason == null) {
            return false;
        }

        return normalize(firstReason).equals(normalize(currentReason));
    }

    /**
     * The verdict an authenticated TVHTML5 /player request gets while the account-bearing TV route
     * is broken server-side: HTTP 200, {@code UNPLAYABLE}, zero formats, reason "The page needs to
     * be reloaded." Observed on the Pixel 9 on 2026-09-07 for both TV_DOWNGRADED (TVHTML5 5.x) and
     * TV (TVHTML5 7.x), on every video tried, in Spanish. yt-dlp carries the same report
     * (yt-dlp/yt-dlp#17389) and demoted the client on 2026-08-18 in commit 5d5b634, which moved
     * {@code web_embedded} ahead of {@code tv_downgraded} in {@code _DEFAULT_AUTHED_CLIENTS}.
     * <p>
     * DIAGNOSTIC ONLY. The list above cannot cover every UI language, so routing must never depend
     * on a hit here - {@code VideoInfoService.isAuthRouteReloadVerdict} keys the actual quarantine
     * off the locale-independent SHAPE of the response and uses this purely to say why in the log.
     */
    public static boolean isReloadPageVerdict(@Nullable String status, @Nullable String reason) {
        if (!STATUS_UNPLAYABLE.equals(status) || reason == null) {
            return false;
        }

        String normalized = normalize(reason);
        for (String known : RELOAD_PAGE_REASONS) {
            if (normalized.contains(known)) {
                return true;
            }
        }
        return false;
    }

    /** Keys of the verdicts that are terminal for the content: two answers agree on the key. */
    private static final String LIVE_RECORDING_GONE = "live-recording-gone";
    private static final String REMOVED_BY_UPLOADER = "removed-by-uploader";
    private static final String ACCOUNT_TERMINATED = "account-terminated";
    private static final String REMOVED_FOR_VIOLATION = "removed-for-violation";

    /**
     * WHOLE verdicts, normalized (see {@link #canonicalReason}), that are terminal for the CONTENT
     * whichever client asks, with their key: a live stream whose recording was never published, a
     * video removed by its uploader, and a channel whose account was terminated. An ALLOWLIST of
     * full sentences in the same six locales as RELOAD_PAGE_REASONS, matched against the whole
     * reason: a qualified variant ("... on this device"), a reworded one or a generic "Video
     * unavailable" does not match, which only means the walk goes on exactly as it always did.
     * Only the Spanish live-recording sentence has been captured on a device (2026-09-25); a wrong
     * guess for another locale fails the same safe way. Copyright takedowns are left out: the
     * claimant's name makes the sentence variable.
     */
    private static final java.util.Map<String, String> TERMINAL_REASONS = new java.util.HashMap<>();

    static {
        terminal(LIVE_RECORDING_GONE,
                "this live stream recording is not available",
                "la grabacion de esta emision en directo no esta disponible",
                "l'enregistrement de cette diffusion en direct n'est pas disponible",
                "die aufzeichnung dieses livestreams ist nicht verfugbar",
                "la registrazione di questo live streaming non e disponibile",
                "a gravacao desta transmissao ao vivo nao esta disponivel");
        terminal(REMOVED_BY_UPLOADER,
                "this video has been removed by the uploader",
                "este video ha sido eliminado por el usuario que lo subio",
                "cette video a ete supprimee par l'utilisateur qui l'a mise en ligne",
                "dieses video wurde vom uploader entfernt",
                "questo video e stato rimosso dall'utente che lo ha caricato",
                "este video foi removido pelo usuario que o enviou");
        terminal(ACCOUNT_TERMINATED,
                "this video is no longer available because the youtube account associated with this"
                        + " video has been terminated",
                "este video ya no esta disponible porque se ha cancelado la cuenta de youtube"
                        + " asociada a este video",
                "cette video n'est plus disponible, car le compte youtube qui lui est associe a ete"
                        + " resilie",
                "dieses video ist nicht mehr verfugbar, weil das mit diesem video verknupfte"
                        + " youtube-konto gekundigt wurde",
                "questo video non e piu disponibile perche l'account youtube associato a questo"
                        + " video e stato chiuso",
                "este video nao esta mais disponivel porque a conta do youtube associada a ele foi"
                        + " encerrada");
    }

    /**
     * A removal for a policy violation names the policy and may add a "learn more" sentence, and
     * the clients word it differently. Captured on the Pixel (2026-09-28, 6SJNVb0GnPI, Spanish):
     * seven clients answered ERROR "Este vídeo se ha retirado por infringir la política de YouTube
     * sobre la incitación al odio. Obtén más información sobre cómo combatir la incitación al odio
     * en tu p..." and WEB_EMBED answered "Este vídeo se ha retirado porque infringía los Términos
     * del Servicio de YouTube" - one verdict, which the whole-sentence rule split in two (and whose
     * second sentence tripped the region veto), so the walk asked all eight sources. So this one
     * verdict is its FIRST sentence starting with one of these removal clauses; every policy and
     * every client's wording is the same key. The non-Spanish forms are unverified guesses.
     */
    private static final String[] VIOLATION_REMOVALS = {
            "this video has been removed for violating youtube's",
            "este video se ha retirado por infringir",
            "este video se ha retirado porque infringia",
            "este video se ha eliminado por infringir",
            "cette video a ete supprimee, car elle ne respectait pas",
            "dieses video wurde entfernt, weil es gegen",
            "questo video e stato rimosso per violazione",
            "este video foi removido por violar",
    };

    /** A second guard: any hint of a region restriction vetoes an allowlisted match. */
    private static final String[] REGION_HINTS = {
            "country", "pais", "pays", "land", "paese", "region",
    };

    private static void terminal(String key, String... reasons) {
        for (String reason : reasons) {
            TERMINAL_REASONS.put(reason, key);
        }
    }

    /**
     * The key of a verdict that is terminal for the content itself (see {@link #TERMINAL_REASONS}
     * and {@link #VIOLATION_REMOVALS}), or null. Only UNPLAYABLE or ERROR can carry one (removed
     * and terminated videos answer ERROR); everything else - another status, an empty, generic,
     * qualified or reworded reason, a region hint in the verdict's sentence - is null. Two clients
     * returning the same key agree on the verdict; this says nothing on its own.
     */
    @Nullable
    public static String definitiveUnplayableKey(@Nullable String status, @Nullable String reason) {
        if ((!STATUS_UNPLAYABLE.equals(status) && !"ERROR".equals(status)) || reason == null) {
            return null;
        }

        String canonical = canonicalReason(reason);
        int sentenceEnd = canonical.indexOf(". ");
        String verdict = sentenceEnd > 0
                ? canonicalReason(canonical.substring(0, sentenceEnd)) : canonical;
        for (String hint : REGION_HINTS) {
            if (verdict.contains(hint)) {
                return null;
            }
        }
        String key = TERMINAL_REASONS.get(canonical);
        if (key != null) {
            return key;
        }
        for (String removal : VIOLATION_REMOVALS) {
            if (verdict.startsWith(removal)) {
                return REMOVED_FOR_VIOLATION;
            }
        }
        return null;
    }

    /**
     * {@link #normalize}d, with curly apostrophes folded and surrounding quotes and trailing
     * sentence punctuation stripped, so the only thing left to compare is the sentence itself.
     */
    private static String canonicalReason(String reason) {
        String value = normalize(reason).replace('\u2019', '\'').replace('\u2018', '\'');
        int start = 0;
        int end = value.length();
        while (end > start && ".!?\"'\u201d\u00bb\u2026 ".indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        while (start < end && "\"'\u201c\u00ab ".indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        return value.substring(start, end);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
