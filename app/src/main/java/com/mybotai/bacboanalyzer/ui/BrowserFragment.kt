package com.mybotai.bacboanalyzer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.mybotai.bacboanalyzer.R

/**
 * Integrated browser with address bar, back/forward/reload/home,
 * loading progress and error handling.
 *
 * JavaScript is enabled because modern gaming platforms require it.
 * All other hardening flags are set: safe browsing on, file/content
 * access off, mixed content never allowed.
 */
class BrowserFragment : Fragment(R.layout.fragment_browser) {

    private var webView: WebView? = null
    private lateinit var progress: ProgressBar
    private lateinit var layoutError: LinearLayout
    private lateinit var editUrl: EditText
    private var backCallback: OnBackPressedCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val web = view.findViewById<WebView>(R.id.webView)
        webView = web
        progress = view.findViewById(R.id.webProgress)
        layoutError = view.findViewById(R.id.layoutError)
        editUrl = view.findViewById(R.id.editUrl)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.endsWith(".apk")) {
                    openExternal(url)
                    true
                } else {
                    false
                }
            }

            override fun onPageFinished(v: WebView, url: String?) {
                editUrl.setText(url ?: "")
                backCallback?.isEnabled = v.canGoBack()
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                editUrl.setText(url ?: "")
                backCallback?.isEnabled = v.canGoBack()
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(true)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.isVisible = newProgress in 1..99
            }
        }

        view.findViewById<Button>(R.id.btnGo).setOnClickListener { loadInput() }
        editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadInput()
                true
            } else {
                false
            }
        }
        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        view.findViewById<ImageButton>(R.id.btnForward).setOnClickListener { if (web.canGoForward()) web.goForward() }
        view.findViewById<ImageButton>(R.id.btnReload).setOnClickListener { showError(false); web.reload() }
        view.findViewById<ImageButton>(R.id.btnHome).setOnClickListener { showError(false); loadHome() }
        view.findViewById<Button>(R.id.btnRetry).setOnClickListener { showError(false); web.reload() }

        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                webView?.let { if (it.canGoBack()) it.goBack() }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        backCallback = callback

        loadHome()
    }

    private fun loadInput() {
        var text = editUrl.text.toString().trim()
        if (text.isEmpty()) return
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "https://$text"
        }
        showError(false)
        webView?.loadUrl(text)
    }

    private fun loadHome() {
        val prefs = requireContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val platform = prefs.getString(MainActivity.KEY_PLATFORM_URL, null)?.trim().orEmpty()
        webView?.loadUrl(if (platform.isNotEmpty()) platform else "file:///android_asset/start.html")
    }

    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(requireContext(), R.string.error_page, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(show: Boolean) {
        layoutError.isVisible = show
    }

    override fun onDestroyView() {
        webView?.let { w ->
            w.loadUrl("about:blank")
            w.clearHistory()
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        webView = null
        backCallback = null
        super.onDestroyView()
    }
}
