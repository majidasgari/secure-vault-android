package ir.maxv.securevault.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity

/**
 * The single activity. A `FragmentActivity` (not a bare `ComponentActivity`) because the fingerprint
 * prompt is `BiometricPrompt`, which needs a `FragmentActivity` host — Compose is unaffected, since
 * `FragmentActivity` is itself a `ComponentActivity`.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(
                color = Color.Transparent,
                modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            ) {
                AppRoot(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }
}
