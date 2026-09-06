package com.pocketgpg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgpg.ui.PocketGpgScreen
import com.pocketgpg.ui.theme.PocketGpgTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketGpgTheme {
                PocketGpgScreen(viewModel = viewModel<MainViewModel>())
            }
        }
    }
}
