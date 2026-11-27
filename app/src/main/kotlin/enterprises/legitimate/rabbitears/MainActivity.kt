package enterprises.legitimate.rabbitears

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import enterprises.legitimate.rabbitears.ui.Root
import enterprises.legitimate.rabbitears.ui.theme.RabbitEarsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = (application as RabbitEarsApp).app
        setContent { RabbitEarsTheme { Root(app) } }
    }
}
