package np.opd.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import np.opd.assistant.ui.OpdApp
import np.opd.assistant.ward.RoundViewModel
import np.opd.assistant.ward.WardViewModel

class MainActivity : ComponentActivity() {

    private val vm: SessionViewModel by viewModels()
    private val wardVm: WardViewModel by viewModels()
    private val roundVm: RoundViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { OpdApp(vm, wardVm, roundVm) }
    }
}
