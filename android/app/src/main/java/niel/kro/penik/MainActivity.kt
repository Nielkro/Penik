package niel.kro.penik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import niel.kro.penik.ui.navigation.NavGraph
import niel.kro.penik.ui.theme.PenikTheme
import niel.kro.penik.ui.theme.ThemeManager

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        enableEdgeToEdge()
        setContent {
            val isLight by ThemeManager.isLight.collectAsState()
            PenikTheme(isLight = isLight) {
                val navController = rememberNavController()
                NavGraph(navController = navController)
            }
        }
    }
}
