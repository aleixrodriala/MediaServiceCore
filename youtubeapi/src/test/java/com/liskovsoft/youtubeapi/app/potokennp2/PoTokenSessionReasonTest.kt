package com.liskovsoft.youtubeapi.app.potokennp2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NEWTUBE(visitor): the reason on the `web-pot-session` NetPath line. A future natural bot wall is
 * read against it (was the session rebuilt because it expired, because a reset asked for it, after
 * a failed mint, or on a rotation?), so the precedence has to be stable. Pure: no WebView.
 */
class PoTokenSessionReasonTest {
    @Test
    fun firstBuildIsInitialWhateverElseIsSet() {
        assertEquals("initial", webPotSessionReason(hadGenerator = false, forceRecreate = true,
            rotation = true, stateCleared = true))
    }

    @Test
    fun failedMintOutranksRotationAndReset() {
        assertEquals("mint-failed", webPotSessionReason(hadGenerator = true, forceRecreate = true,
            rotation = true, stateCleared = true))
    }

    @Test
    fun rotationOutranksTheResetItAlwaysComesWith() {
        // PoTokenGate.rotateWebVisitor arms the rotation AND clears the session state.
        assertEquals("rotation", webPotSessionReason(hadGenerator = true, forceRecreate = false,
            rotation = true, stateCleared = true))
    }

    @Test
    fun resetAndExpiry() {
        assertEquals("reset", webPotSessionReason(hadGenerator = true, forceRecreate = false,
            rotation = false, stateCleared = true))
        assertEquals("expired", webPotSessionReason(hadGenerator = true, forceRecreate = false,
            rotation = false, stateCleared = false))
    }
}
