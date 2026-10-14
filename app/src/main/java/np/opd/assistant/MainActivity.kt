package np.opd.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import np.opd.assistant.ui.OpdApp

class MainActivity : ComponentActivity() {

    private val vm: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { OpdApp(vm) }
    }
}
