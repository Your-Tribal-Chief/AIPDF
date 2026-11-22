package com.allpdf.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.allpdf.pro.core.Files
import com.allpdf.pro.core.Store
import com.allpdf.pro.ui.AllPdfTheme
import com.allpdf.pro.ui.HomeScreen
import com.allpdf.pro.ui.Nav
import com.allpdf.pro.ui.Screen
import com.allpdf.pro.ui.ToolScreen
import com.allpdf.pro.ui.ViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AllPdfTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val n = remember { nav }
                    BackHandler(enabled = n.stack.size > 1) { n.pop() }
                    AnimatedContent(targetState = n.top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { screen ->
                        when (screen) {
                            is Screen.Home -> HomeScreen(n)
                            is Screen.ToolS -> ToolScreen(screen.session, n)
                            is Screen.Viewer -> ViewerScreen(screen.file, n)
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                  else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Files.importUri(this@MainActivity, uri) }
                Store.addRecent(uri.toString(), f.name)
                nav.openViewer(f)
            } catch (_: Exception) {}
        }
    }
}
