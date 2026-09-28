package com.liskovsoft.youtubeapi.service;

/**
 * NEWTUBE(bench): keeps a build from writing to the signed-in account's watch history - the
 * watch-time pings that fill it, and pausing, resuming or clearing it. Benchmark builds set it
 * (MobileMainApplication): the signed-in /player measurements run on the owner's own account (his
 * OK, 2026-09-29) and must leave his history and recommendations as they were. Signing in used to
 * RESUME a paused history account-wide (YouTubeContentService.enableHistory).
 */
public final class AccountWrites {
    private static volatile boolean sHistoryBlocked;

    private AccountWrites() {
    }

    public static void setHistoryBlocked(boolean blocked) {
        sHistoryBlocked = blocked;
    }

    public static boolean isHistoryBlocked() {
        return sHistoryBlocked;
    }

    static boolean blocked(String what) {
        if (sHistoryBlocked) {
            android.util.Log.d("NetPath", "account-write blocked what=" + what);
        }
        return sHistoryBlocked;
    }
}
