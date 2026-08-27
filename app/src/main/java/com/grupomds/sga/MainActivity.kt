package com.grupomds.sga

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grupomds.sga.ui.SgaApp
import com.grupomds.sga.ui.SgaViewModel
import com.grupomds.sga.ui.theme.SgaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SgaTheme {
                val vm: SgaViewModel = viewModel()
                SgaApp(vm)
            }
        }
    }
}
