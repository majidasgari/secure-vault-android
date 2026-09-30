package ir.maxv.securevault.core

/**
 * Which screen the app belongs on.
 *
 * Kept out of the Android layer so the rule can be tested: the vault's own presence decides between
 * "set up", "unlock" and "browse". Any code path that changes what the local mirror holds must ask
 * this, not remember a stage from startup — the first device run sat on the setup form after a
 * *successful* sync because the stage had only been computed once, when the mirror was still empty.
 */
enum class AppStage { SETUP, LOCKED, UNLOCKED }

object StageLogic {
    fun stage(hasVaultMetadata: Boolean, hasOpenSession: Boolean): AppStage = when {
        hasOpenSession -> AppStage.UNLOCKED
        hasVaultMetadata -> AppStage.LOCKED
        else -> AppStage.SETUP
    }
}
