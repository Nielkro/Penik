package niel.kro.penik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import niel.kro.penik.ui.navigation.NavGraph
import niel.kro.penik.ui.theme.PenikTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PenikTheme {
                val navController = rememberNavController()
                NavGraph(navController = navController)
            }
        }
    }
}
