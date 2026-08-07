package com.example

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.CartinoViewModel
import com.example.ui.MainScreen
import com.example.ui.theme.CartinoTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: CartinoViewModel = viewModel()
            val themeMode by viewModel.appThemeMode.collectAsState()
            val accentPalette by viewModel.appAccentPalette.collectAsState()

            CartinoTheme(
                themeMode = themeMode,
                accentPalette = accentPalette
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
