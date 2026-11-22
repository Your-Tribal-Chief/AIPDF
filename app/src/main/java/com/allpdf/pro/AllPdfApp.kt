package com.allpdf.pro

import android.app.Application
import android.webkit.WebView
import com.allpdf.pro.core.Env
import com.allpdf.pro.core.Store
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class AllPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Env.init(this)
        Store.init(this)
        PDFBoxResourceLoader.init(this)
        try { WebView.enableSlowWholeDocumentDraw() } catch (_: Throwable) {}
        Thread { Env.clearOldWork() }.start()
    }
}
