package com.webinspector.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.*
import android.os.*
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.view.animation.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var toolBar: LinearLayout
    private lateinit var bottomSheet: LinearLayout
    private var inspectorMode = false
    private var currentUrl = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ───────────────────────────── dp helper ─────────────────────────────
    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    private fun dp(v: Int) = dp(v.toFloat())

    // ─────────────────────────── color palette ───────────────────────────
    private val C = object {
        val bg          = Color.parseColor("#0A0A0F")
        val glass       = Color.parseColor("#1C1C2E")
        val glassBorder = Color.parseColor("#2C2C3E")
        val accent      = Color.parseColor("#6C63FF")
        val accentDark  = Color.parseColor("#4A42CC")
        val accentGlow  = Color.parseColor("#3D3A80")
        val danger      = Color.parseColor("#FF3B6B")
        val success     = Color.parseColor("#30D158")
        val text        = Color.parseColor("#FFFFFF")
        val textSub     = Color.parseColor("#8E8EA0")
        val white20     = Color.parseColor("#33FFFFFF")
        val white10     = Color.parseColor("#1AFFFFFF")
        val white05     = Color.parseColor("#0DFFFFFF")
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DRAWABLES
    // ═══════════════════════════════════════════════════════════════════════

    private fun glassDrawable(radius: Float = 24f, stroke: Boolean = true): Drawable {
        val r = dp(radius)
        return object : Drawable() {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1f).toFloat()
                color = C.glassBorder
            }
            val path = Path()

            override fun draw(canvas: Canvas) {
                val rect = bounds.toRectF()
                path.reset()
                path.addRoundRect(rect, r.toFloat(), r.toFloat(), Path.Direction.CW)
                // backdrop blur simulation — layered semi-transparent fills
                paint.color = Color.parseColor("#14FFFFFF"); canvas.drawPath(path, paint)
                paint.color = C.glass;                       canvas.drawPath(path, paint)
                paint.color = C.white05;                     canvas.drawPath(path, paint)
                if (stroke) canvas.drawPath(path, strokeP)
            }
            override fun setAlpha(a: Int) { paint.alpha = a }
            override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }

    private fun accentDrawable(radius: Float = 16f): Drawable {
        val r = dp(radius).toFloat()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(C.accent, C.accentDark)
        ).apply { cornerRadius = r }
    }

    private fun rippleDrawable(base: Drawable, color: Int = C.white20): Drawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(color), base, null)

    // ═══════════════════════════════════════════════════════════════════════
    //  SVG ICONS  (all custom, no system icons)
    // ═══════════════════════════════════════════════════════════════════════

    private fun svgToBitmap(svg: String, size: Int): Bitmap {
        val html = """<html><body style="margin:0;background:transparent">
            <svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 24 24">
            $svg</svg></body></html>"""
        // Use canvas-based approach: parse SVG manually via Path for key icons
        // We'll render via a tiny offscreen WebView trick — but simpler: draw with Canvas
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    }

    // Draw icon on canvas directly — pure Kotlin/Canvas
    private fun iconBitmap(type: IconType, sizeDp: Int = 24, color: Int = C.text): Bitmap {
        val s = dp(sizeDp)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = s * 0.08f
        }
        val fp = Paint(p).apply { style = Paint.Style.FILL }
        val sc = s / 24f  // scale factor

        fun x(v: Float) = v * sc
        fun y(v: Float) = v * sc

        when (type) {
            IconType.BACK -> {
                val path = Path().apply {
                    moveTo(x(15f), y(5f)); lineTo(x(8f), y(12f)); lineTo(x(15f), y(19f))
                }
                c.drawPath(path, p)
            }
            IconType.FORWARD -> {
                val path = Path().apply {
                    moveTo(x(9f), y(5f)); lineTo(x(16f), y(12f)); lineTo(x(9f), y(19f))
                }
                c.drawPath(path, p)
            }
            IconType.REFRESH -> {
                val oval = RectF(x(3f), y(3f), x(19f), y(19f))
                val path = Path().apply {
                    arcTo(oval, -90f, 270f)
                    moveTo(x(12f), y(2f)); lineTo(x(12f), y(5f))
                    moveTo(x(12f), y(2f)); lineTo(x(15f), y(5f))
                }
                c.drawPath(path, p)
            }
            IconType.INSPECT -> {
                // Crosshair / cursor
                p.style = Paint.Style.STROKE
                c.drawCircle(x(12f), y(12f), x(5f), p)
                c.drawLine(x(12f), y(2f), x(12f), y(7f), p)
                c.drawLine(x(12f), y(17f), x(12f), y(22f), p)
                c.drawLine(x(2f), y(12f), x(7f), y(12f), p)
                c.drawLine(x(17f), y(12f), x(22f), y(12f), p)
                fp.color = color
                c.drawCircle(x(12f), y(12f), x(2f), fp)
            }
            IconType.CODE -> {
                val path = Path().apply {
                    moveTo(x(8f), y(6f)); lineTo(x(2f), y(12f)); lineTo(x(8f), y(18f))
                    moveTo(x(16f), y(6f)); lineTo(x(22f), y(12f)); lineTo(x(16f), y(18f))
                }
                c.drawPath(path, p)
            }
            IconType.FOLDER -> {
                val path = Path().apply {
                    moveTo(x(2f), y(7f))
                    lineTo(x(2f), y(19f)); lineTo(x(22f), y(19f)); lineTo(x(22f), y(9f))
                    lineTo(x(11f), y(9f)); lineTo(x(9f), y(7f)); close()
                    moveTo(x(22f), y(9f)); lineTo(x(2f), y(9f))
                }
                c.drawPath(path, p)
            }
            IconType.DOWNLOAD -> {
                val path = Path().apply {
                    moveTo(x(12f), y(3f)); lineTo(x(12f), y(15f))
                    moveTo(x(7f), y(10f)); lineTo(x(12f), y(15f)); lineTo(x(17f), y(10f))
                    moveTo(x(3f), y(19f)); lineTo(x(21f), y(19f))
                }
                c.drawPath(path, p)
            }
            IconType.CLOSE -> {
                c.drawLine(x(5f), y(5f), x(19f), y(19f), p)
                c.drawLine(x(19f), y(5f), x(5f), y(19f), p)
            }
            IconType.SEARCH -> {
                c.drawCircle(x(10f), y(10f), x(6f), p)
                c.drawLine(x(15f), y(15f), x(21f), y(21f), p)
            }
            IconType.STAR -> {
                val path = Path().apply {
                    moveTo(x(12f), y(2f))
                    lineTo(x(15.09f), y(8.26f)); lineTo(x(22f), y(9.27f))
                    lineTo(x(17f), y(14.14f)); lineTo(x(18.18f), y(21.02f))
                    lineTo(x(12f), y(17.77f)); lineTo(x(5.82f), y(21.02f))
                    lineTo(x(7f), y(14.14f)); lineTo(x(2f), y(9.27f))
                    lineTo(x(8.91f), y(8.26f)); close()
                }
                c.drawPath(path, p)
            }
        }
        return bmp
    }

    enum class IconType { BACK, FORWARD, REFRESH, INSPECT, CODE, FOLDER, DOWNLOAD, CLOSE, SEARCH, STAR }

    // ═══════════════════════════════════════════════════════════════════════
    //  ICON BUTTON FACTORY
    // ═══════════════════════════════════════════════════════════════════════

    private fun iconBtn(
        icon: IconType,
        color: Int = C.text,
        sizeDp: Int = 22,
        padDp: Int = 10,
        glassBack: Boolean = false
    ): ImageView {
        val iv = ImageView(this)
        iv.setImageBitmap(iconBitmap(icon, sizeDp, color))
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = dp(padDp)
        iv.setPadding(p, p, p, p)
        if (glassBack) {
            iv.background = rippleDrawable(glassDrawable(14f))
        } else {
            iv.background = rippleDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return iv
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEXT FACTORY
    // ═══════════════════════════════════════════════════════════════════════

    private fun label(text: String, sizeSp: Float = 14f, color: Int = C.text, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        return tv
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  onCreate
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        root = FrameLayout(this)
        root.setBackgroundColor(C.bg)
        setContentView(root)

        buildWebView()
        buildTopBar()
        buildToolBar()
        buildInspectorOverlay()

        // insets
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = WindowInsetsCompat.toWindowInsetsCompat(insets)
            val top = sys.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bot = sys.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            toolBar.updatePadding(bottom = bot + dp(8))
            insets
        }

        webView.loadUrl("https://google.com")
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WEBVIEW
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        }
        webView.addJavascriptInterface(WebBridge(), "WebInspector")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                urlBar.setText(url)
                urlBar.setSelection(0)
                injectInspectorCSS()
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = false
        }
        webView.webChromeClient = WebChromeClient()
        root.addView(webView)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TOP BAR  (glass URL bar)
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTopBar() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            setPadding(dp(12), dp(52), dp(12), dp(8))
        }

        // Glass pill
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassDrawable(32f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        }

        // Back / Forward / Refresh
        val back = iconBtn(IconType.BACK).also {
            it.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        }
        val fwd = iconBtn(IconType.FORWARD).also {
            it.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        }
        val ref = iconBtn(IconType.REFRESH).also {
            it.setOnClickListener { webView.reload() }
        }

        // URL input
        urlBar = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = null
            setTextColor(C.text)
            setHintTextColor(C.textSub)
            hint = "Search or enter URL"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(); true } else false
            }
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) selectAll()
            }
        }

        val searchIcon = iconBtn(IconType.SEARCH, C.textSub, 18, 8)
        searchIcon.setOnClickListener { navigate() }

        pill.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        pill.addView(fwd,  LinearLayout.LayoutParams(dp(40), dp(40)))
        pill.addView(searchIcon, LinearLayout.LayoutParams(dp(36), dp(40)))
        pill.addView(urlBar)
        pill.addView(ref, LinearLayout.LayoutParams(dp(40), dp(40)))

        container.addView(pill)

        // Blur-like gradient behind top bar
        val grad = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT)
            )
        }
        root.addView(grad)
        root.addView(container)
    }

    private fun navigate() {
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" "))
                "https://$url"
            else
                "https://www.google.com/search?q=${url.replace(" ", "+")}"
        }
        webView.loadUrl(url)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BOTTOM TOOLBAR
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildToolBar() {
        toolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val grad = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(120), Gravity.BOTTOM
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT)
            )
        }
        root.addView(grad)

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glassDrawable(28f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun toolBtn(icon: IconType, label: String, accent: Boolean = false, action: () -> Unit): LinearLayout {
            val ll = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = rippleDrawable(
                    if (accent) accentDrawable(14f) else glassDrawable(14f),
                    C.white20
                )
                setOnClickListener { action() }
            }
            val iv = ImageView(this).apply {
                setImageBitmap(iconBitmap(icon, 20, if (accent) C.text else C.text))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            val tv = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(if (accent) C.text else C.textSub)
                gravity = Gravity.CENTER
            }
            ll.addView(iv, LinearLayout.LayoutParams(dp(28), dp(28)))
            ll.addView(tv, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            return ll
        }

        val inspBtn = toolBtn(IconType.INSPECT, "Inspect", true) { toggleInspector() }
        val codeBtn = toolBtn(IconType.CODE, "Source") { showPageSource() }
        val filesBtn = toolBtn(IconType.FOLDER, "Files") { showFileStructure() }

        pill.addView(inspBtn,  LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(4),0,dp(4),0) })
        pill.addView(codeBtn,  LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(4),0,dp(4),0) })
        pill.addView(filesBtn, LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(4),0,dp(4),0) })

        toolBar.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(toolBar)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INSPECTOR OVERLAY
    // ═══════════════════════════════════════════════════════════════════════

    private lateinit var inspectorBanner: LinearLayout
    private lateinit var inspectedPanel: LinearLayout

    private fun buildInspectorOverlay() {
        // Top banner shown when inspector mode is active
        inspectorBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = object : Drawable() {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun draw(canvas: Canvas) {
                    p.color = Color.parseColor("#CC6C63FF")
                    val r = dp(20f).toFloat()
                    canvas.drawRoundRect(bounds.toRectF(), r, r, p)
                }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(cf: ColorFilter?) {}
                @Suppress("OVERRIDE_DEPRECATION")
                override fun getOpacity() = PixelFormat.TRANSLUCENT
            }
            setPadding(dp(16), dp(8), dp(8), dp(8))
            visibility = View.GONE
            elevation = dp(8f)
        }

        val bannerText = label("Tap any element to inspect", 13f, C.text)
        bannerText.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val cancelBtn = iconBtn(IconType.CLOSE, C.text, 18, 8).also {
            it.setOnClickListener { toggleInspector() }
        }
        inspectorBanner.addView(iconBtn(IconType.INSPECT, C.text, 18, 0).apply {
            setPadding(0, 0, dp(8), 0)
        })
        inspectorBanner.addView(bannerText)
        inspectorBanner.addView(cancelBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        val bannerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply { setMargins(dp(16), dp(108), dp(16), 0) }
        root.addView(inspectorBanner, bannerParams)

        // Bottom panel for inspected element
        inspectedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(24f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
            elevation = dp(12f)
        }
        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(320),
            Gravity.BOTTOM
        ).apply { setMargins(dp(8), 0, dp(8), dp(88)) }
        root.addView(inspectedPanel, panelParams)
    }

    private fun toggleInspector() {
        inspectorMode = !inspectorMode
        if (inspectorMode) {
            inspectorBanner.visibility = View.VISIBLE
            animateIn(inspectorBanner)
            webView.evaluateJavascript(getInspectorJS(), null)
        } else {
            inspectorBanner.animate().alpha(0f).setDuration(200).withEndAction {
                inspectorBanner.visibility = View.GONE
                inspectorBanner.alpha = 1f
            }.start()
            inspectedPanel.animate().translationY(dp(400f)).setDuration(300)
                .withEndAction { inspectedPanel.visibility = View.GONE; inspectedPanel.translationY = 0f }
                .start()
            webView.evaluateJavascript("javascript:window.__wi_cleanup&&window.__wi_cleanup();", null)
        }
    }

    private fun animateIn(v: View) {
        v.alpha = 0f
        v.translationY = -dp(20f)
        v.animate().alpha(1f).translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun showInspectedElement(tag: String, id: String, classes: String, html: String, css: String) {
        inspectedPanel.removeAllViews()

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagBadge = label(" <$tag> ", 12f, C.accent, true).apply {
            background = GradientDrawable().apply {
                setColor(C.accentGlow); cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val closeBtn = iconBtn(IconType.CLOSE, C.textSub, 16, 6).also {
            it.setOnClickListener {
                inspectedPanel.animate().translationY(dp(400f)).setDuration(250)
                    .withEndAction { inspectedPanel.visibility = View.GONE; inspectedPanel.translationY = 0f }.start()
            }
        }
        header.addView(tagBadge)
        if (id.isNotEmpty()) {
            val idBadge = label(" #$id ", 11f, C.success, true).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A30D158")); cornerRadius = dp(8f).toFloat()
                }
                setPadding(dp(6), dp(3), dp(6), dp(3))
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(6), 0, 0, 0)
            }
            header.addView(idBadge)
        }
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
        inspectedPanel.addView(header)

        // Tabs
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(C.white10); cornerRadius = dp(12f).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(12), 0, dp(8))
        }
        inspectedPanel.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
        ).apply { setMargins(0, dp(12), 0, dp(8)) })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val contentArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(contentArea)
        inspectedPanel.addView(scroll)

        fun showTab(which: Int) {
            contentArea.removeAllViews()
            when (which) {
                0 -> {  // HTML Editor
                    val et = EditText(this).apply {
                        setText(formatHtml(html))
                        setTextColor(Color.parseColor("#E2E8F0"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        background = GradientDrawable().apply { setColor(C.white10); cornerRadius = dp(12f).toFloat() }
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        typeface = Typeface.MONOSPACE
                        setHorizontallyScrolling(false)
                        gravity = Gravity.TOP
                        minLines = 4
                    }
                    contentArea.addView(et, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    // Apply button
                    val applyBtn = buildPrimaryButton("Apply Changes") {
                        val newHtml = et.text.toString()
                        val escaped = newHtml.replace("\\", "\\\\").replace("'", "\\'")
                            .replace("\n", "\\n").replace("\r", "")
                        webView.evaluateJavascript(
                            "javascript:if(window.__wi_selected){window.__wi_selected.outerHTML='$escaped';}", null
                        )
                        showToast("Applied!")
                    }
                    contentArea.addView(applyBtn, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                    ).apply { setMargins(0, dp(8), 0, 0) })
                }
                1 -> {  // CSS
                    val tv = TextView(this).apply {
                        text = formatCss(css)
                        setTextColor(Color.parseColor("#A8DADC"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        background = GradientDrawable().apply { setColor(C.white10); cornerRadius = dp(12f).toFloat() }
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        typeface = Typeface.MONOSPACE
                    }
                    contentArea.addView(tv)
                }
                2 -> {  // Preview box
                    val wv = WebView(this).apply {
                        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12f).toFloat() }
                        settings.javaScriptEnabled = true
                        loadDataWithBaseURL(currentUrl,
                            "<html><body style='margin:8px;background:#fff'>$html</body></html>",
                            "text/html", "UTF-8", null)
                    }
                    contentArea.addView(wv, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
                    ))
                }
            }
        }

        // Build tabs
        val tabNames = listOf("HTML", "CSS", "Preview")
        var activeTab = 0
        val tabBtns = mutableListOf<TextView>()

        tabNames.forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (i == 0) C.text else C.textSub)
                background = if (i == 0) GradientDrawable().apply {
                    setColor(C.accent); cornerRadius = dp(10f).toFloat()
                } else null
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    tabBtns[activeTab].setTextColor(C.textSub)
                    tabBtns[activeTab].background = null
                    activeTab = i
                    setTextColor(C.text)
                    background = GradientDrawable().apply { setColor(C.accent); cornerRadius = dp(10f).toFloat() }
                    showTab(i)
                }
            }
            tabBtns.add(tv)
            tabBar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        showTab(0)

        // Slide up animation
        inspectedPanel.visibility = View.VISIBLE
        inspectedPanel.translationY = dp(400f)
        inspectedPanel.animate().translationY(0f).setDuration(350)
            .setInterpolator(DecelerateInterpolator(2.5f)).start()
    }

    private fun buildPrimaryButton(text: String, action: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(C.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rippleDrawable(accentDrawable(12f))
            setOnClickListener { action() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PAGE SOURCE
    // ═══════════════════════════════════════════════════════════════════════

    private fun showPageSource() {
        webView.evaluateJavascript(
            "javascript:(function(){return document.documentElement.outerHTML})();"
        ) { result ->
            val html = result?.removeSurrounding("\"")
                ?.replace("\\n", "\n")?.replace("\\t", "\t")?.replace("\\\"", "\"")
                ?: "Error"
            showCodeSheet("Page Source", html, "html")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCodeSheet(title: String, code: String, lang: String) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_NoTitleBar_Fullscreen)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#CC000000"))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(28f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(16f)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label(title, 16f, C.text, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val downloadBtn = iconBtn(IconType.DOWNLOAD, C.accent, 20, 8, true).also {
            it.setOnClickListener {
                saveToFile("${title.replace(" ", "_")}.${lang}", code)
                dialog.dismiss()
            }
        }
        header.addView(downloadBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(iconBtn(IconType.CLOSE, C.textSub, 18, 8).also {
            it.setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        sheet.addView(header)

        // Stats
        val lines = code.lines().size
        sheet.addView(label("$lines lines · ${code.length} chars", 11f, C.textSub).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        // Code view
        val scrollV = ScrollView(this)
        val scrollH = HorizontalScrollView(this)
        val tv = TextView(this).apply {
            text = highlightCode(code, lang)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        scrollH.addView(tv)
        scrollV.addView(scrollH)
        sheet.addView(scrollV, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val sheetParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.85).toInt(),
            Gravity.BOTTOM
        ).apply { setMargins(dp(8), 0, dp(8), dp(16)) }
        root.addView(sheet, sheetParams)

        dialog.setContentView(root)
        dialog.show()

        sheet.translationY = dp(800f)
        sheet.animate().translationY(0f).setDuration(400)
            .setInterpolator(DecelerateInterpolator(2.5f)).start()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  FILE STRUCTURE
    // ═══════════════════════════════════════════════════════════════════════

    private fun showFileStructure() {
        val loadingSheet = showLoadingSheet("Analyzing site structure…")
        webView.evaluateJavascript(getFileStructureJS()) { result ->
            loadingSheet.dismiss()
            try {
                val json = result?.removeSurrounding("\"")?.replace("\\\"", "\"")
                    ?.replace("\\n", "")?.let {
                        // Unescape JSON string returned by evaluateJavascript
                        it
                    }
                // Parse and show
                showFileStructureDialog(currentUrl, result ?: "")
            } catch (e: Exception) {
                showToast("Error: ${e.message}")
            }
        }
    }

    private fun showFileStructureDialog(baseUrl: String, rawJson: String) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_NoTitleBar_Fullscreen)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#CC000000"))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(28f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(16f)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("Site Files", 18f, C.text, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val dlBtn = buildPrimaryButton("Download ZIP") {
            dialog.dismiss()
            downloadSiteZip(baseUrl, rawJson)
        }

        header.addView(iconBtn(IconType.CLOSE, C.textSub, 18, 8).also {
            it.setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        sheet.addView(header)

        sheet.addView(label("Files found on this page", 12f, C.textSub).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        // File list
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Parse URLs from JS result
        val urls = parseResourceUrls(rawJson, baseUrl)
        urls.groupBy { getFileCategory(it) }.forEach { (cat, files) ->
            // Category header
            list.addView(label(cat.uppercase(), 10f, C.accent, true).apply {
                setPadding(dp(4), dp(12), 0, dp(4))
            })
            files.forEach { fileUrl ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rippleDrawable(GradientDrawable().apply {
                        setColor(C.white10); cornerRadius = dp(10f).toFloat()
                    })
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                val icon = when (cat) {
                    "Scripts" -> IconType.CODE
                    "Styles" -> IconType.STAR
                    "Images" -> IconType.FOLDER
                    else -> IconType.DOWNLOAD
                }
                row.addView(iconBtn(icon, C.textSub, 16, 0))
                val nameLabel = label(fileUrl.substringAfterLast("/").take(40), 12f, C.text)
                nameLabel.setPadding(dp(8), 0, 0, 0)
                nameLabel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(nameLabel)

                row.setOnClickListener { showCodeSheet(fileUrl.substringAfterLast("/"), fileUrl, "url") }
                list.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(2), 0, dp(2)) })
            }
        }

        scroll.addView(list)
        sheet.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Download button at bottom
        sheet.addView(dlBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { setMargins(0, dp(12), 0, 0) })

        val sheetParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.85).toInt(),
            Gravity.BOTTOM
        ).apply { setMargins(dp(8), 0, dp(8), dp(16)) }
        root.addView(sheet, sheetParams)

        dialog.setContentView(root)
        dialog.show()

        sheet.translationY = dp(800f)
        sheet.animate().translationY(0f).setDuration(400)
            .setInterpolator(DecelerateInterpolator(2.5f)).start()
    }

    private fun parseResourceUrls(json: String, baseUrl: String): List<String> {
        // Extract URLs from JSON string
        val urls = mutableListOf<String>()
        val patterns = listOf(
            Regex("\"(https?://[^\"]+\\.(js|css|png|jpg|jpeg|gif|svg|woff|woff2|ttf|ico))\""),
            Regex("\"(/[^\"]+\\.(js|css|png|jpg|jpeg|gif|svg|woff|woff2|ttf|ico))\"")
        )
        patterns.forEach { regex ->
            regex.findAll(json).forEach { match ->
                var url = match.groupValues[1]
                if (url.startsWith("/")) {
                    val base = baseUrl.substringBefore("://") + "://" + baseUrl.substringAfter("://").substringBefore("/")
                    url = base + url
                }
                if (!urls.contains(url)) urls.add(url)
            }
        }
        return urls.take(100)
    }

    private fun getFileCategory(url: String) = when {
        url.endsWith(".js") -> "Scripts"
        url.endsWith(".css") -> "Styles"
        url.matches(Regex(".*\\.(png|jpg|jpeg|gif|svg|ico)")) -> "Images"
        url.matches(Regex(".*\\.(woff|woff2|ttf|eot)")) -> "Fonts"
        else -> "Other"
    }

    private fun downloadSiteZip(baseUrl: String, json: String) {
        val urls = parseResourceUrls(json, baseUrl)
        if (urls.isEmpty()) { showToast("No downloadable files found"); return }

        showToast("Downloading ${urls.size} files…")
        scope.launch(Dispatchers.IO) {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val zipFile = File(dir, "site_${System.currentTimeMillis()}.zip")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    // Add index.html
                    webView.post {
                        webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                            scope.launch(Dispatchers.IO) {
                                val entry = ZipEntry("index.html")
                                zos.putNextEntry(entry)
                                zos.write((html?.removeSurrounding("\"") ?: "").toByteArray())
                                zos.closeEntry()
                            }
                        }
                    }
                    Thread.sleep(1000)

                    urls.take(50).forEach { url ->
                        try {
                            val conn = URL(url).openConnection() as HttpURLConnection
                            conn.connectTimeout = 5000
                            conn.readTimeout = 5000
                            conn.connect()
                            if (conn.responseCode == 200) {
                                val name = url.substringAfterLast("/").take(80)
                                zos.putNextEntry(ZipEntry(name))
                                conn.inputStream.copyTo(zos)
                                zos.closeEntry()
                            }
                        } catch (_: Exception) {}
                    }
                }
                withContext(Dispatchers.Main) {
                    showToast("Saved: ${zipFile.absolutePath}")
                    shareFile(zipFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Error: ${e.message}") }
            }
        }
    }

    private fun shareFile(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share ZIP"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  JAVASCRIPT INJECTION
    // ═══════════════════════════════════════════════════════════════════════

    private fun injectInspectorCSS() {
        val css = """
            .wi-highlight {
                outline: 2px solid #6C63FF !important;
                outline-offset: 2px !important;
                background: rgba(108,99,255,0.08) !important;
                transition: outline 0.15s, background 0.15s !important;
            }
            .wi-selected {
                outline: 3px solid #6C63FF !important;
                box-shadow: 0 0 0 4px rgba(108,99,255,0.3) !important;
            }
        """.trimIndent()
        val js = """
            (function(){
                var s = document.getElementById('__wi_style');
                if(!s){s=document.createElement('style');s.id='__wi_style';document.head.appendChild(s);}
                s.textContent = `$css`;
            })();
        """.trimIndent()
        webView.evaluateJavascript("javascript:$js", null)
    }

    private fun getInspectorJS(): String = """
        javascript:(function(){
            window.__wi_cleanup = function(){
                document.querySelectorAll('.wi-highlight,.wi-selected').forEach(function(el){
                    el.classList.remove('wi-highlight','wi-selected');
                });
                document.removeEventListener('mouseover',window.__wi_over,true);
                document.removeEventListener('click',window.__wi_click,true);
            };
            window.__wi_selected = null;
            window.__wi_over = function(e){
                document.querySelectorAll('.wi-highlight').forEach(function(el){el.classList.remove('wi-highlight');});
                e.target.classList.add('wi-highlight');
            };
            window.__wi_click = function(e){
                e.preventDefault(); e.stopPropagation();
                document.querySelectorAll('.wi-selected').forEach(function(el){el.classList.remove('wi-selected');});
                e.target.classList.add('wi-selected');
                window.__wi_selected = e.target;
                var el = e.target;
                var tag = el.tagName.toLowerCase();
                var id = el.id || '';
                var cls = Array.from(el.classList).filter(function(c){return c!=='wi-highlight'&&c!=='wi-selected';}).join(' ');
                var html = el.outerHTML.substring(0,3000);
                var cs = window.getComputedStyle(el);
                var cssProps = ['display','position','width','height','margin','padding','color',
                    'background','font-size','font-weight','font-family','border','border-radius',
                    'opacity','z-index','flex','grid','transform','box-shadow'];
                var css = cssProps.map(function(p){return p+': '+cs.getPropertyValue(p)+';'}).join('\n');
                window.WebInspector.onElementSelected(tag,id,cls,
                    encodeURIComponent(html), encodeURIComponent(css));
            };
            document.addEventListener('mouseover',window.__wi_over,true);
            document.addEventListener('click',window.__wi_click,true);
        })();
    """.trimIndent()

    private fun getFileStructureJS(): String = """
        (function(){
            var resources = {
                scripts: Array.from(document.querySelectorAll('script[src]')).map(function(s){return s.src;}),
                styles: Array.from(document.querySelectorAll('link[rel=stylesheet]')).map(function(l){return l.href;}),
                images: Array.from(document.querySelectorAll('img[src]')).map(function(i){return i.src;}),
                fonts: [],
                links: Array.from(document.querySelectorAll('a[href]')).map(function(a){return a.href;}).filter(function(h){return h.startsWith('http');}).slice(0,30)
            };
            try{
                var sheets = Array.from(document.styleSheets);
                sheets.forEach(function(sheet){
                    try{
                        var rules = Array.from(sheet.cssRules||[]);
                        rules.forEach(function(r){
                            if(r.type===5){resources.fonts.push(r.cssText.match(/url\(['""]?([^'""]+)['""]?\)/)||'');}
                        });
                    }catch(e){}
                });
            }catch(e){}
            return JSON.stringify(resources);
        })();
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════════
    //  JAVA BRIDGE
    // ═══════════════════════════════════════════════════════════════════════

    inner class WebBridge {
        @JavascriptInterface
        fun onElementSelected(tag: String, id: String, classes: String, htmlEnc: String, cssEnc: String) {
            val html = try { java.net.URLDecoder.decode(htmlEnc, "UTF-8") } catch (e: Exception) { htmlEnc }
            val css  = try { java.net.URLDecoder.decode(cssEnc, "UTF-8") }  catch (e: Exception) { cssEnc }
            runOnUiThread { showInspectedElement(tag, id, classes, html, css) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun formatHtml(html: String): String {
        var indent = 0
        val sb = StringBuilder()
        val tokens = html.split(Regex("(?<=>)(?=<)|(?<=[^>])(?=<)"))
        tokens.forEach { token ->
            val t = token.trim()
            if (t.startsWith("</")) {
                indent = (indent - 2).coerceAtLeast(0)
                sb.append(" ".repeat(indent)).append(t).append("\n")
            } else if (t.startsWith("<") && !t.startsWith("<!--") && !t.endsWith("/>")) {
                sb.append(" ".repeat(indent)).append(t).append("\n")
                if (!t.contains("</")) indent += 2
            } else {
                sb.append(" ".repeat(indent)).append(t).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun formatCss(css: String) = css.replace(";", ";\n").replace("{", "{\n").replace("}", "\n}\n")

    private fun highlightCode(code: String, lang: String): android.text.SpannableString {
        val span = android.text.SpannableString(code)
        // Simple syntax coloring
        val keywords = when (lang) {
            "html" -> Regex("<[^>]+>|</[^>]+>|<!DOCTYPE[^>]+>")
            "css" -> Regex("[a-z-]+(?=\\s*:)|\\{|\\}|:[^;]+;")
            else -> null
        }
        keywords?.findAll(code)?.forEach { match ->
            span.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#79C0FF")),
                match.range.first, match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return span
    }

    private fun showLoadingSheet(msg: String): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassDrawable(20f)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val pb = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(C.accent) }
        val tv = label(msg, 14f, C.text).apply { setPadding(dp(12), 0, 0, 0) }
        v.addView(pb, LinearLayout.LayoutParams(dp(24), dp(24)))
        v.addView(tv)
        dialog.setContentView(v)
        dialog.show()
        return dialog
    }

    private fun saveToFile(name: String, content: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, name).writeText(content)
            showToast("Saved to ${dir.absolutePath}/$name")
        } catch (e: Exception) {
            showToast("Save error: ${e.message}")
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            val toast = Toast.makeText(this, msg, Toast.LENGTH_LONG)
            toast.show()
        }
    }

    override fun onBackPressed() {
        when {
            inspectorMode -> toggleInspector()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webView.destroy()
    }
}

// ── Extension ───────────────────────────────────────────────────────────────
fun Rect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
fun View.updatePadding(
    left: Int = paddingLeft, top: Int = paddingTop,
    right: Int = paddingRight, bottom: Int = paddingBottom
) = setPadding(left, top, right, bottom)
