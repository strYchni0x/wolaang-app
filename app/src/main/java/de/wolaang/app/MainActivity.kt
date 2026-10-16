package de.wolaang.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.wolaang.app.ui.WolaangNavHost
import de.wolaang.app.ui.theme.WolaangTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WolaangTheme {
                WolaangNavHost()
            }
        }
    }
}
