package com.allpdf.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch every heavy dependency so the pipeline proves they resolve and dex.
        val probe = listOf(
            com.tom_roush.pdfbox.pdmodel.PDDocument::class.java.name,
            com.google.mlkit.vision.text.latin.TextRecognizerOptions::class.java.name,
            org.apache.poi.hssf.usermodel.HSSFWorkbook::class.java.name,
            org.apache.poi.hwpf.extractor.WordExtractor::class.java.name,
        ).joinToString("\n")
        setContent { MaterialTheme { Text("AllPDF Pro skeleton\n$probe") } }
    }
}
