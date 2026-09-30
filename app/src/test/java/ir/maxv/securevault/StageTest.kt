package ir.maxv.securevault

import ir.maxv.securevault.core.AppStage
import ir.maxv.securevault.core.StageLogic
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rule that moves the app from the setup form to the password screen and back. */
class StageTest {

    @Test
    fun `an empty device asks for setup`() {
        assertEquals(AppStage.SETUP, StageLogic.stage(hasVaultMetadata = false, hasOpenSession = false))
    }

    @Test
    fun `once the vault lands the app asks for the password, not the bucket`() {
        // the regression from the first device run: sync finished and the app stayed on setup
        assertEquals(AppStage.LOCKED, StageLogic.stage(hasVaultMetadata = true, hasOpenSession = false))
    }

    @Test
    fun `an open session wins over everything`() {
        assertEquals(AppStage.UNLOCKED, StageLogic.stage(hasVaultMetadata = true, hasOpenSession = true))
        assertEquals(AppStage.UNLOCKED, StageLogic.stage(hasVaultMetadata = false, hasOpenSession = true))
    }

    @Test
    fun `wiping the mirror sends the app back to setup`() {
        val before = StageLogic.stage(hasVaultMetadata = true, hasOpenSession = true)
        val after = StageLogic.stage(hasVaultMetadata = false, hasOpenSession = false)
        assertEquals(AppStage.UNLOCKED, before)
        assertEquals(AppStage.SETUP, after)
    }
}
