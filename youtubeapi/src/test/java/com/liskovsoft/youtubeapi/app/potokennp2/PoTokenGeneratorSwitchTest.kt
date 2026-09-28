package com.liskovsoft.youtubeapi.app.potokennp2

import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenWebView
import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenWebView4
import com.liskovsoft.youtubeapi.app.potokennp2.misc.PoTokenGeneratorSwitch
import com.liskovsoft.youtubeapi.app.potokennp2.misc.selectFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEWTUBE(pot-wv4): PoTokenWebView stays the generator unless the phone's debug switch
 * (debug.arc.pot_gen=v4) asks for PoTokenWebView4. Pure: the factories are companion objects, and
 * nothing here builds a WebView.
 */
class PoTokenGeneratorSwitchTest {
    @After
    fun restoreDefault() {
        PoTokenGeneratorSwitch.select(PoTokenGeneratorSwitch.LEGACY)
    }

    @Test
    fun theDefaultIsTheCurrentGenerator() {
        assertEquals(PoTokenGeneratorSwitch.LEGACY, PoTokenGeneratorSwitch.getSelected())
        assertSame(PoTokenWebView, selectFactory())
    }

    @Test
    fun v4SelectsTheHomepageGeneratorAndReachesTheProvider() {
        assertTrue(PoTokenGeneratorSwitch.select("v4"))

        assertSame(PoTokenWebView4, selectFactory())
        // PoTokenGate may have been initialized before the switch was read: the provider's factory
        // is what the next generator build uses.
        assertSame(PoTokenWebView4, PoTokenProviderImpl.poTokenFactory)

        assertTrue(PoTokenGeneratorSwitch.select(PoTokenGeneratorSwitch.LEGACY))
        assertSame(PoTokenWebView, PoTokenProviderImpl.poTokenFactory)
    }

    @Test
    fun unknownNamesLeaveTheSelectionAlone() {
        for (name in listOf(null, "", "4", "1", "webview4", "none")) {
            assertFalse("name: $name", PoTokenGeneratorSwitch.select(name))
            assertSame(PoTokenWebView, selectFactory())
        }

        // setprop values are trimmed and case-insensitive
        assertTrue(PoTokenGeneratorSwitch.select(" V4 "))
        assertSame(PoTokenWebView4, selectFactory())
    }
}
