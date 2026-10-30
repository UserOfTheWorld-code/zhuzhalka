package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SimpleVpnModeTest {
    @Test
    fun simpleStartAlwaysPreparesAndroidVpnForEveryStoredModeCombination() {
        listOf<String?>(null, "PROXY", AppConfig.VPN).forEach { originalMode ->
            listOf(false, true).forEach { originalRoot ->
                val storage = ModeStorage(originalMode, originalRoot)
                assertEquals(originalMode, storage.mode)
                assertEquals(originalRoot, storage.root)

                storage.prepare()

                assertEquals(AppConfig.VPN, storage.mode)
                assertFalse(storage.root)
            }
        }
    }

    @Test
    fun alreadyPreparedVpnDoesNotRewriteSettings() {
        val storage = ModeStorage(AppConfig.VPN, false)

        storage.prepare()

        assertEquals(0, storage.writes)
    }

    @Test
    fun rootWriteFailureRestoresPreviousModeAndRootSetting() {
        val storage = ModeStorage("PROXY", true).apply { failRootOnce = true }

        assertThrows(IllegalStateException::class.java) { storage.prepare() }

        assertEquals("PROXY", storage.mode)
        assertEquals(true, storage.root)
    }

    @Test
    fun silentWriteFailureIsCaughtByReadbackAndRestored() {
        val storage = ModeStorage("PROXY", true).apply { ignoreRootOnce = true }

        assertThrows(IllegalStateException::class.java) { storage.prepare() }

        assertEquals("PROXY", storage.mode)
        assertEquals(true, storage.root)
    }

    @Test
    fun rollbackPreservesAnUnsetMode() {
        val storage = ModeStorage(null, true).apply { failRootOnce = true }

        assertThrows(IllegalStateException::class.java) { storage.prepare() }

        assertEquals(null, storage.mode)
        assertEquals(true, storage.root)
    }

    private class ModeStorage(var mode: String?, var root: Boolean) {
        var writes = 0
        var failRootOnce = false
        var ignoreRootOnce = false

        fun prepare() = SettingsManager.prepareSimpleVpnMode(
            readMode = { mode },
            readRoot = { root },
            writeMode = { writes++; mode = it; true },
            writeRoot = {
                writes++
                when {
                    failRootOnce -> { failRootOnce = false; false }
                    ignoreRootOnce -> { ignoreRootOnce = false; true }
                    else -> { root = it; true }
                }
            },
        )
    }
}
