package com.example.mirrorbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private class Pane(val web: WebView, val frame: FrameLayout)

    private val panes = mutableListOf<Pane>() // index 0 = source
    private val ui = Handler(Looper.getMainLooper())
    private var paused = false
    private lateinit var script: String
    private lateinit var container: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var pauseBtn: Button

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        script = assets.open("mirror.js").bufferedReader().use { it.readText() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        urlInput = EditText(this).apply {
            hint = "Site URL (e.g. example.com)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> loadAll(); true }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(urlInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        row1.addView(button("Load") { loadAll() })

        pauseBtn = button("Pause") {
            paused = !paused
            pauseBtn.text = if (paused) "Resume" else "Pause"
        }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            pauseBtn,
            button("+ Tab") { if (panes.size < 7) addPane() else toast("Max 6 mirror tabs") },
            button("- Tab") { removePane() },
            button("Sync URL") { syncUrls() }
        ).forEach { row2.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }

        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(row1, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        addPane() // source (green border)
        addPane() // first mirror (gray border)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun normalize(u: String): String =
        if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"

    @SuppressLint("SetJavaScriptEnabled")
    private fun addPane() {
        val isSource = panes.isEmpty()
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(isSource), "AndroidMirror")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(script, null)
            }
        }
        val frame = FrameLayout(this).apply {
            setPadding(4, 4, 4, 4)
            setBackgroundColor(if (isSource) Color.rgb(46, 160, 67) else Color.GRAY)
        }
        frame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        panes.add(Pane(web, frame))
        relayout()

        val typed = urlInput.text.toString().trim()
        val url = panes.first().web.url ?: if (typed.isNotEmpty()) normalize(typed) else null
        url?.let { web.loadUrl(it) }
    }

    private fun removePane() {
        if (panes.size <= 2) { toast("Need at least 1 mirror tab"); return }
        val p = panes.removeAt(panes.size - 1)
        relayout()
        p.frame.removeAllViews()
        p.web.destroy()
    }

    private fun relayout() {
        panes.forEach { (it.frame.parent as? ViewGroup)?.removeView(it.frame) }
        container.removeAllViews()
        val cols = if (panes.size <= 2) 1 else 2
        panes.chunked(cols).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { row.addView(it.frame, LinearLayout.LayoutParams(0, MATCH, 1f)) }
            repeat(cols - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f)) }
            container.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    private fun loadAll() {
        val typed = urlInput.text.toString().trim()
        if (typed.isEmpty()) return
        val url = normalize(typed)
        panes.forEach { it.web.loadUrl(url) }
    }

    private fun syncUrls() {
        val url = panes.first().web.url ?: return
        panes.drop(1).forEach { it.web.loadUrl(url) }
        toast("Mirror tabs reloaded to source URL")
    }

    /** One bridge per WebView; only the source's events are forwarded. */
    inner class Bridge(private val source: Boolean) {
        @JavascriptInterface
        fun isSource(): Boolean = source

        @JavascriptInterface
        fun send(json: String) {
            if (!source || paused) return
            val arg = JSONObject.quote(json)
            ui.post {
                for (i in 1 until panes.size) {
                    panes[i].web.evaluateJavascript(
                        "window.__mirrorReplay && window.__mirrorReplay($arg)", null
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (panes.first().web.canGoBack()) {
            panes.forEach { if (it.web.canGoBack()) it.web.goBack() }
        } else super.onBackPressed()
    }

    override fun onDestroy() {
        panes.forEach { it.web.destroy() }
        super.onDestroy()
    }
}
