package com.example.mirrorbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private class Pane(val web: WebView, val frame: FrameLayout, val blocker: View)
    private enum class Mode { GRID, FOCUS, HIDDEN }

    private val panes = mutableListOf<Pane>() // index 0 = source
    private val ui = Handler(Looper.getMainLooper())
    private val queue = ConcurrentLinkedQueue<String>()
    private val flushing = AtomicBoolean(false)
    private var paused = false
    private var mode = Mode.GRID
    private var lastW = 0
    private var lastH = 0
    private var fullH = 0
    private var stripView: View? = null
    private var srcFrame: View? = null
    private lateinit var script: String
    private lateinit var container: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var pauseBtn: Button
    private lateinit var modeBtn: Button

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MAX_PANES = 11 // 1 source + 10 mirrors

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
        modeBtn = button("View: Grid") {
            mode = Mode.values()[(mode.ordinal + 1) % 3]
            modeBtn.text = "View: " + mode.name.lowercase().replaceFirstChar { it.uppercase() }
            if (mode == Mode.HIDDEN) toast("Mirrors keep running in the background")
            relayout()
        }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            pauseBtn,
            button("+Tab") { if (panes.size < MAX_PANES) addPane() else toast("Max 10 mirror tabs") },
            button("-Tab") { removePane() },
            button("Sync") { syncUrls() },
            modeBtn
        ).forEach { row2.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(row1, LinearLayout.LayoutParams(MATCH, WRAP))
        controls.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP))

        val barBtn = button("▲ Hide controls") { }
        barBtn.setOnClickListener {
            val show = controls.visibility != View.VISIBLE
            controls.visibility = if (show) View.VISIBLE else View.GONE
            barBtn.text = if (show) "▲ Hide controls" else "▼ Show controls"
        }

        container = FrameLayout(this)
        container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (w != lastW) {
                // first layout or rotation: full rebuild
                lastW = w; lastH = h; fullH = h
                v.post { relayout() }
            } else if (h != lastH) {
                // keyboard opened or closed: resize in place, never rebuild
                lastH = h
                if (h > fullH) fullH = h
                val kb = h < fullH * 0.8f
                stripView?.visibility = if (kb) View.GONE else View.VISIBLE
                srcFrame?.let { f ->
                    val lp = f.layoutParams
                    lp.height = if (kb) h else fullH - fullH / 4
                    f.layoutParams = lp
                }
            }
        }

        root.addView(barBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        addPane() // source (green border)
        addPane() // first mirror
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(6, 0, 6, 0)
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
        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        web.addJavascriptInterface(Bridge(isSource), "AndroidMirror")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(script, null)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                toast("A tab ran out of memory. Remove some tabs.")
                return true
            }
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(if (isSource) Color.rgb(46, 160, 67) else Color.GRAY)
        }
        frame.addView(web)
        val blocker = View(this).apply { isClickable = true }
        panes.add(Pane(web, frame, blocker))
        relayout()

        val typed = urlInput.text.toString().trim()
        val url = panes.first().web.url ?: if (typed.isNotEmpty()) normalize(typed) else null
        url?.let { web.loadUrl(it) }
    }

    private fun removePane() {
        if (panes.size <= 2) { toast("Need at least 1 mirror tab"); return }
        val p = panes.removeAt(panes.size - 1)
        (p.frame.parent as? ViewGroup)?.removeView(p.frame)
        p.frame.removeAllViews()
        p.web.destroy()
        relayout()
    }

    private fun fit(p: Pane, w: Int, h: Int, s: Float, pad: Int) {
        p.frame.setPadding(pad, pad, pad, pad)
        p.web.pivotX = 0f
        p.web.pivotY = 0f
        p.web.scaleX = s
        p.web.scaleY = s
        p.web.layoutParams = FrameLayout.LayoutParams(w, h)
    }

    private fun relayout() {
        val w = container.width
        val h = container.height
        if (w == 0 || h == 0 || panes.isEmpty()) return
        stripView = null
        srcFrame = null
        panes.forEach { p ->
            (p.frame.parent as? ViewGroup)?.removeView(p.frame)
            p.frame.removeView(p.blocker)
            p.frame.visibility = View.VISIBLE
            fit(p, MATCH, MATCH, 1f, 4)
        }
        container.removeAllViews()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when (mode) {
            Mode.GRID -> {
                val cols = if (panes.size <= 2) 1 else 2
                panes.chunked(cols).forEach { g ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    g.forEach { row.addView(it.frame, LinearLayout.LayoutParams(0, MATCH, 1f)) }
                    repeat(cols - g.size) { row.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f)) }
                    root.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
                }
                container.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            Mode.FOCUS -> {
                val stripH = h / 4
                val srcH = h - stripH
                val mw = w - 8
                val mh = srcH - 8
                val s = stripH.toFloat() / mh
                root.addView(panes[0].frame, LinearLayout.LayoutParams(MATCH, srcH))
                val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                panes.drop(1).forEach { p ->
                    fit(p, mw, mh, s, 0)
                    p.frame.addView(p.blocker, FrameLayout.LayoutParams(MATCH, MATCH))
                    strip.addView(p.frame, LinearLayout.LayoutParams((mw * s).toInt(), stripH).apply { rightMargin = 6 })
                }
                val sv = HorizontalScrollView(this).apply { addView(strip) }
                stripView = sv
                srcFrame = panes[0].frame
                root.addView(sv, LinearLayout.LayoutParams(MATCH, stripH))
                container.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            Mode.HIDDEN -> {
                panes.drop(1).forEach { p ->
                    p.frame.visibility = View.INVISIBLE
                    container.addView(p.frame, FrameLayout.LayoutParams(MATCH, MATCH))
                }
                container.addView(panes[0].frame, FrameLayout.LayoutParams(MATCH, MATCH))
            }
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

    private fun flush() {
        flushing.set(false)
        val list = ArrayList<String>()
        while (true) list.add(queue.poll() ?: break)
        if (list.isEmpty()) return
        val arg = JSONObject.quote("[" + list.joinToString(",") + "]")
        val js = "window.__mirrorReplay && window.__mirrorReplay($arg)"
        for (i in 1 until panes.size) panes[i].web.evaluateJavascript(js, null)
    }

    /** One bridge per WebView; only the source's events are forwarded, in batches. */
    inner class Bridge(private val source: Boolean) {
        @JavascriptInterface
        fun isSource(): Boolean = source

        @JavascriptInterface
        fun send(json: String) {
            if (!source || paused) return
            queue.add(json)
            if (flushing.compareAndSet(false, true)) ui.postDelayed({ flush() }, 8)
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
