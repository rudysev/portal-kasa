package com.portal.kasa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portal.commons.DebugLog
import com.portal.kasa.ui.PlugsScreen
import com.portal.kasa.ui.PlugsViewModel
import com.portal.kasa.ui.theme.KasaTheme
import java.io.File

/**
 * The Kasa app's only screen: discover plugs on the LAN and list/toggle them — no sign-in (local control
 * needs no account). The voice integration is separate — the [com.portal.kasa.provider.KasaToolProvider] is
 * started on demand by the assistant and shares the same repository (via [Graph]), so the app needs no
 * resident service.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.file = File(getExternalFilesDir(null), "debug.txt")
        val repository = Graph.repository(applicationContext)
        setContent {
            KasaTheme {
                val viewModel: PlugsViewModel = viewModel(factory = PlugsViewModel.factory(repository))
                PlugsScreen(viewModel)
            }
        }
    }
}
