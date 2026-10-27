package io.github.korenkrita.simulsub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var preview: CaptionOverlayView
    private lateinit var updater: AppUpdater

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private lateinit var permList: LinearLayout

    /** Re-reads the style prefs into the sliders and preview; set up in [setupStyle]. */
    private var loadStyle: () -> Unit = {}
    private var modeGroup: com.google.android.material.button.MaterialButtonToggleGroup? = null
    /** While visible: the overlay toolbar is not focusable, so using it does not pause/resume this screen. */
    private val styleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in STYLE_KEYS) loadStyle()
        // the overlay toolbar flipped the mode: keep the home screen's choice in sync
        if (key == "finalOnly") syncMode()
    }

    private fun syncMode() = modeGroup?.let { g ->
        (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it.tag == prefs.finalOnly }?.let { if (g.checkedButtonId != it.id) g.check(it.id) }
    }

    // App-chosen day / night: override the night bit before any resource is read, so values-night and the
    // DayNight theme follow it. "system" leaves the configuration alone.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        val night = when (Prefs(base).theme) {
            "light" -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            "dark" -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        applyOverrideConfiguration(android.content.res.Configuration().apply {
            fontScale = 0f // "unset": a fresh Configuration says 1.0, which would override the user's font size
            uiMode = (base.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_JpSub)
        setContentView(R.layout.activity_main)
        // targetSdk 35 draws edge-to-edge: the status bar, the navigation bar, a cutout and the keyboard are
        // paddings here, not black strips. The action bar keeps the navigation inset itself (its button must not
        // sit in the gesture area); the page takes the keyboard's height while the bar is away, so a field on the
        // settings pages can be scrolled above the keyboard.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            else null
            @Suppress("DEPRECATION") // API 29 has no WindowInsets.Type; these still work on 30+
            val top = bars?.top ?: insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = bars?.bottom ?: insets.systemWindowInsetBottom
            statusInset = top
            navInset = bottom
            imeInset = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            applyInsets()
            insets
        }
        prefs = Prefs(this)

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        progress = findViewById(R.id.progress)
        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)
        permList = findViewById(R.id.permList)
        setupStyle()
        renderTranslate()
        findViewById<LinearLayout>(R.id.themeBox).apply {
            addView(hint("这个页面用白天还是夜间颜色。不影响悬浮字幕，字幕颜色在「字幕」里选。").apply { setPadding(0, 0, 0, (10 * dpx).toInt()) })
            // recreate re-reads resources with the new mode; the overlay lives in the service and is untouched
            addView(choice(listOf("system" to "跟随系统", "light" to "白天", "dark" to "夜间"), prefs.theme) {
                if (prefs.theme != it) { prefs.theme = it; recreate() }
            })
        }
        // the five sections of the PC sidebar as a bottom bar. The saved one is read first, so the bar comes up
        // with the right tab already lit (a theme change recreates the activity on 页面)
        page = savedInstanceState?.getInt("page", 0)?.coerceIn(PAGES.indices) ?: 0
        PAGES.forEachIndexed { i, s -> findViewById<View>(s.nav).setOnClickListener { selectPage(i) } }
        findViewById<TextView>(R.id.aboutVersion).text = "${getString(R.string.app_name)}  版本 ${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.openRepo).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            catch (_: android.content.ActivityNotFoundException) { }
        }
        setupSession()
        selectPage(page)
        updater = AppUpdater(this)
        // a rotation or theme change is not an entry into the app (unless it cancelled a check that was running)
        configRecreated = savedInstanceState?.getBoolean("configChange") == true
        manualInterrupted = savedInstanceState != null && manualCheckStopped
        manualCheckStopped = false
    }

    /** Index into [PAGES]: the section on screen. */
    private var page = 0

    /** One section of the bottom bar: its page, its tab and the title the header shows for it. */
    private data class Section(val root: Int, val nav: Int, val title: String)

    private val PAGES = listOf(
        Section(R.id.pageHome, R.id.navHome, "同传字幕"),
        Section(R.id.pageCaption, R.id.navCaption, "字幕"),
        Section(R.id.pageTranslate, R.id.navTranslate, "翻译"),
        Section(R.id.pagePage, R.id.navPage, "页面"),
        Section(R.id.pageRecords, R.id.navRecords, "记录"),
    )

    // insets from the root view's listener (onCreate): the status bar, the navigation bar, the keyboard
    private var statusInset = 0
    private var navInset = 0
    private var imeInset = 0

    /**
     * The status bar above the header, the navigation bar under the bottom bar (so the tabs never sit in the
     * gesture area), and the keyboard's height on the page while the action bar is away — that is the settings
     * pages, where the fields are.
     */
    private fun applyInsets() {
        val barShown = findViewById<View>(R.id.actionBar).visibility == View.VISIBLE
        findViewById<View>(R.id.root).setPadding(0, statusInset, 0, 0)
        findViewById<View>(R.id.scroll).setPadding(0, 0, 0, if (barShown) 0 else maxOf(navInset, imeInset))
        findViewById<View>(R.id.actionBar).setPadding(0, 0, 0, navInset)
        findViewById<View>(R.id.navBar).setPadding((6 * dpx).toInt(), (4 * dpx).toInt(), (6 * dpx).toInt(), (4 * dpx).toInt() + navInset)
    }

    /** A page coming in: a short rise and fade, so a section switch does not just flash. */
    private fun reveal(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = 8 * dpx
        // the end action repeats what the animator finishes with: a cancelled animation must never leave it at 0
        v.animate().alpha(1f).translationY(0f).setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { v.alpha = 1f; v.translationY = 0f }.start()
    }

    /**
     * One section of the PC sidebar, on screen. The bar, the header title and the one action bar follow it; the
     * 字幕 page re-measures its live preview once it is laid out, 记录 rebuilds its list.
     */
    private fun selectPage(i: Int) {
        page = i.coerceIn(PAGES.indices)
        PAGES.forEachIndexed { n, s ->
            findViewById<View>(s.root).visibility = if (n == page) View.VISIBLE else View.GONE
            findViewById<View>(s.nav).isSelected = n == page
            findViewById<TextView>(s.nav).typeface = if (n == page) android.graphics.Typeface.DEFAULT_BOLD
                else android.graphics.Typeface.DEFAULT
        }
        findViewById<TextView>(R.id.pageTitle).text = PAGES[page].title
        findViewById<View>(R.id.pageSub).visibility = if (page == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.actionBar).visibility = if (page == 0) View.VISIBLE else View.GONE
        findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        applyInsets()
        reveal(findViewById<View>(PAGES[page].root))
        when (page) {
            0 -> { renderSession(); sizeSessionScroll(true) }
            1 -> { loadStyle(); findViewById<View>(R.id.previewStage).post { loadStyle() } }
            2 -> renderLang()
            4 -> renderLogs()
        }
    }

    /** One step back: any section returns to 首页 before the app closes. */
    private fun back(): Boolean = if (page != 0) { selectPage(0); true } else false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
        // recreated right away for a rotation / theme (no check then), unless that stops a check under way; a state
        // saved for a process the system may end is not this, and the restored app checks again
        outState.putBoolean("configChange", isChangingConfigurations && !updater.checking)
    }

    /** this activity only replaces one a configuration change ended; its first start is not an entry into the app */
    private var configRecreated = false
    /** ... and that change stopped a manual check */
    private var manualInterrupted = false

    override fun onStart() {
        super.onStart()
        // each entry into the app (a launch, or coming back from the background) checks for an update once
        updater.start(configRecreated, manualInterrupted)
        configRecreated = false; manualInterrupted = false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!back()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** The home card's two questions, side by side: 识别语种 → 字幕的语言。下次开始字幕时生效。 */
    private fun renderLang() {
        val box = findViewById<LinearLayout>(R.id.langBox)
        box.removeAllViews()
        val running = CaptionService.running
        fun weighted(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        // DIY: 左边换成「识别语种」——识别引擎只有 SenseVoice，能认的语种就是这几个。
        // 「自动」时把翻译的源语言也交给模型判断。
        // 语种码来自模型本身：sherpa-onnx 明确只接受 auto / zh / en / ja / ko / yue
        val langs = listOf("ja" to "日语", "auto" to "自动（中日英韩粤）", "zh" to "中文", "ko" to "韩语", "yue" to "粤语")
        val cur = langs.indexOfFirst { it.first == prefs.asrSvLang }.coerceAtLeast(0)
        box.addView(weighted(dropdown(langs.map { it.second }, cur, true, wrap = true) {
            val code = langs[it].first
            if (prefs.asrSvLang != code) {
                prefs.asrSvLang = code
                prefs.sourceLang = Lang.of(code) // 识别成什么语种，翻译的源语言就跟着它
                renderTranslate()
            }
        }).apply { contentDescription = "识别语种" })
        box.addView(TextView(this).apply {
            text = "→"; textSize = 16f; setTextColor(getColor(R.color.muted)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding((10 * dpx).toInt(), 0, (10 * dpx).toInt(), 0)
        })
        // the presets, the typed language in use (if any), and 其他 to type one; the list is rebuilt after each change,
        // so a cancelled 其他 shows the language in use again
        val presets = Target.PRESETS
        val current = prefs.targetLang
        val targets = if (current in presets) presets else presets + current
        val items = targets.map { it.zh } + "自己填…"
        box.addView(weighted(dropdown(items, targets.indexOf(current), true, wrap = true) {
            if (it == targets.size) askTargetName()
            else if (prefs.targetLang != targets[it]) { prefs.targetLang = targets[it]; renderTranslate(); main.post { renderLang() } }
        }).apply { contentDescription = "字幕翻译成" })
        findViewById<TextView>(R.id.langHint).text = if (running) "识别语种 → 字幕的语言。字幕运行中，语种改了要重新开始字幕才生效。"
            else "识别语种 → 字幕的语言。纯日语直播选「日语」最准；中日混说才用「自动」。"
    }

    /** 其他（自己填）: a language name typed in, saved with 确定; cancel or a blank name keeps the language in use. */
    private fun askTargetName() {
        val now = prefs.targetLang.takeIf { it.lang == null }?.name ?: ""
        val input = android.widget.EditText(this).asField().apply {
            setText(now); setSelection(now.length); isSingleLine = true; hint = "例如 繁体中文、粤语、文言文"
            filters = arrayOf(android.text.InputFilter.LengthFilter(Target.MAX_NAME))
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((22 * dpx).toInt(), (8 * dpx).toInt(), (22 * dpx).toInt(), 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("字幕翻译成")
            .setMessage("写上想要的语言，会原样交给你填的 LLM 去翻译。")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                Target.custom(input.text.toString())?.let { if (it != prefs.targetLang) { prefs.targetLang = it; renderTranslate() } }
            }
            // whatever happened, the dropdown shows the language in use again (not 其他)
            .setOnDismissListener { renderLang() }
            .show()
    }

    private fun askProfileName(p: LlmProfile?, now: String, rename: Boolean) {
        val input = android.widget.EditText(this).asField().apply {
            setText(now); setSelection(now.length); isSingleLine = true; hint = "例如 千问、DeepSeek"
            filters = arrayOf(android.text.InputFilter.LengthFilter(LlmProfile.MAX_NAME))
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((22 * dpx).toInt(), (8 * dpx).toInt(), (22 * dpx).toInt(), 0); addView(input)
        }
        val err = android.widget.TextView(this).apply {
            textSize = 14f; setTextColor(getColor(R.color.err)); setPadding((22 * dpx).toInt(), (8 * dpx).toInt(), (22 * dpx).toInt(), 0)
        }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(box); addView(err) }
        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (rename) "重命名配置" else "新建配置")
            .setView(outer)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        d.show()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = input.text.toString()
            val (ok, e) = LlmProfile.name(typed, prefs.byokProfiles, self = p?.id)
            if (ok != null) {
                if (rename) prefs.renameByokProfile(p!!, ok) else prefs.addByokProfile(ok).also { byokDraft = null; byokResult = null }
                d.dismiss(); renderTranslate()
            } else err.text = e
        }
    }

    /**
     * 编辑 a scene ([old]) or 新建 one (null): name, background, terms one per line. 生成术语 asks the LLM API for up to
     * the typed number of terms and only appends them (lines already there, saved or typed, stay as they are).
     * 保存 checks the text and keeps the dialog open on a mistake; 删除 a user's scene, 恢复默认 an edited built-in.
     */
    private fun editScene(old: Scene?) {
        fun area(value: String, hintText: String, lines: Int) = android.widget.EditText(this).asField().apply {
            setText(value); hint = hintText; textSize = 15f; setTextColor(getColor(R.color.text))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = lines; maxLines = 8; gravity = Gravity.TOP or Gravity.START
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dpx).toInt(), (4 * dpx).toInt(), (22 * dpx).toInt(), 0)
        }
        val name = android.widget.EditText(this).asField().apply {
            setText(old?.label ?: ""); hint = "例如 游戏实况、某主播的直播"; isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(Scene.MAX_LABEL))
        }
        val background = area(old?.background ?: "", "这是什么内容、谁在说话、在聊什么，翻译会参考", 2)
        // the terms: one row of two boxes each (原文 | 译文), the arrow between them is ours
        val termBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun termBox(field: Int) = (0 until termBox.childCount).map { (termBox.getChildAt(it) as LinearLayout).getChildAt(field) as android.widget.EditText }
        fun rows() = termBox(0).zip(termBox(2)) { a, b -> a.text.toString() to b.text.toString() }
        fun addRow(src: String, dst: String) {
            fun box(v: String, h: String) = android.widget.EditText(this).asField().apply {
                setText(v); hint = h; isSingleLine = true; textSize = 15f; setTextColor(getColor(R.color.text))
                filters = arrayOf(android.text.InputFilter.LengthFilter(Scene.MAX_TERM))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(box(src, "原文"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = Scene.TERM_SEP; textSize = 15f; setTextColor(getColor(R.color.muted)); setPadding((6 * dpx).toInt(), 0, (6 * dpx).toInt(), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(box(dst, "译文"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "×"; textSize = 18f; setTextColor(getColor(R.color.text2)); minWidth = 0; minimumWidth = 0
                setPadding((10 * dpx).toInt(), 0, (10 * dpx).toInt(), 0); contentDescription = "删掉这条"
                setOnClickListener { termBox.removeView(row) }
            })
            termBox.addView(row)
        }
        old?.terms?.forEach { addRow(it.first, it.second) }
        if (termBox.childCount == 0) addRow("", "")
        val addTerm = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "+ 加一条"; textSize = 14f
            setOnClickListener { if (termBox.childCount < Scene.MAX_TERMS) addRow("", "") }
        }
        val count = android.widget.EditText(this).apply {
            setText("20"); isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_NUMBER; textSize = 15f
            filters = arrayOf(android.text.InputFilter.LengthFilter(3)); contentDescription = "最多生成几条"
        }
        val generate = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "生成术语" }
        val status = hint("")
        form.addView(label("名字", 8)); form.addView(name)
        form.addView(label("背景说明")); form.addView(background)
        // the language the 译文 are in: the terms are used only when the captions are in it
        val captions = prefs.targetLang
        var termsLang = old?.termsLang ?: captions
        val langs = (Target.PRESETS + termsLang + captions).distinct()
        val langHint = hint("")
        fun showLangHint() {
            langHint.text = if (termsLang == captions) "现在字幕翻成${captions.zh}，这些术语会用上。"
                else "现在字幕翻成${captions.zh}，这些${termsLang.zh}术语不会用上；字幕换成${termsLang.zh}才用。"
        }
        showLangHint()
        form.addView(label("术语"))
        form.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = "译文是"; textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, 0, (12 * dpx).toInt(), 0) })
            addView(dropdown(langs.map { it.zh }, langs.indexOf(termsLang), true) { termsLang = langs[it]; showLangHint() }
                .apply { contentDescription = "术语的译文是哪种语言" }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        form.addView(langHint)
        form.addView(hint("左边写视频里说的词，右边写想要的译法，例如 ボス 和 头目。"))
        form.addView(termBox); form.addView(addTerm)
        form.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(generate)
            addView(TextView(this@MainActivity).apply { text = "最多"; textSize = 15f; setTextColor(getColor(R.color.text2)); setPadding((12 * dpx).toInt(), 0, (4 * dpx).toInt(), 0) })
            addView(count, LinearLayout.LayoutParams((56 * dpx).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply { text = "条"; textSize = 15f; setTextColor(getColor(R.color.text2)) })
        })
        form.addView(status)
        form.addView(hint("「生成术语」用你填的 LLM API，按名字和背景说明想出这类内容的常见词，只往下面加，不改也不删已有的术语。"))

        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (old == null) "新建内容类型" else "编辑「${old.label}」")
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
        when {
            old != null && !old.builtin -> b.setNeutralButton("删除", null)
            old != null && !old.shipped -> b.setNeutralButton("恢复默认", null)
        }
        val dialog = b.show()
        var generating = false

        generate.setOnClickListener {
            if (generating) return@setOnClickListener
            val service = prefs.byokService()
            if (service == null || service.model.isEmpty()) { status.text = "先在上面「LLM API」里填好服务并保存。"; return@setOnClickListener }
            val chat = service.url
            val label = name.text.toString().trim()
            if (label.isEmpty() && background.text.isBlank()) { status.text = "先写名字或背景说明，LLM 才知道要哪类术语。"; return@setOnClickListener }
            val n = count.text.toString().toIntOrNull()
            if (n == null || n < 1 || n > MAX_GENERATE) { status.text = "生成条数填 1 到 $MAX_GENERATE。"; return@setOnClickListener }
            val room = Scene.MAX_TERMS - rows().count { it.first.isNotBlank() || it.second.isNotBlank() }
            if (room <= 0) { status.text = "术语已经有 ${Scene.MAX_TERMS} 条，满了。"; return@setOnClickListener }
            generating = true; generate.isEnabled = false; status.text = "正在生成…（最多一分钟）"
            val model = prefs.byokModel; val stored = prefs.byokVariant(chat, model)
            val bg = background.text.toString(); val src = prefs.sourceLang; val tgt = termsLang
            val have = Scene.sources(rows())
            Thread {
                val (got, err) = generateTerms(service, stored, { prefs.setByokVariant(chat, model, it) }, label, bg, have, minOf(n, room), src, tgt)
                main.post {
                    generating = false; generate.isEnabled = true
                    if (!dialog.isShowing || isDestroyed) return@post
                    // appended to what is in the box now (the user may have typed meanwhile), skipping 原文 now there
                    val now = rows()
                    val (next, added) = appendTerms(now, got.orEmpty())
                    status.text = when {
                        err != null -> "⚠ 没能生成：$err"
                        added == 0 -> "没有生成新的术语（可能都已经有了，或者满了），可以把背景说明写详细些再试。"
                        else -> {
                            // a lone empty row (the one a new scene starts with) gives way to the generated ones
                            if (now.size == 1 && now[0].first.isBlank() && now[0].second.isBlank()) termBox.removeAllViews()
                            next.drop(now.size).forEach { addRow(it.first, it.second) }
                            "加了 $added 条，看一下有没有不对的，再点「保存」。"
                        }
                    }
                }
            }.start()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val (s, err) = Scene.edit(old?.id ?: "u${System.currentTimeMillis()}", name.text.toString(), background.text.toString(), rows(), termsLang)
            if (s == null) { status.text = "⚠ $err"; return@setOnClickListener }
            val list = prefs.scenes
            prefs.scenes = if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
            prefs.scene = s
            dialog.dismiss(); renderTranslate()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            old ?: return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(if (old.builtin) "把「${old.label}」恢复成自带的说明和术语？你改过的内容会丢掉。" else "删除「${old.label}」？")
                .setNegativeButton("取消", null)
                .setPositiveButton(if (old.builtin) "恢复默认" else "删除") { _, _ ->
                    val shipped = Scene.DEFAULTS.firstOrNull { it.id == old.id }
                    val inUse = prefs.scene.id == old.id
                    prefs.scenes = if (shipped != null) prefs.scenes.map { if (it.id == old.id) shipped else it }
                        else prefs.scenes.filter { it.id != old.id }
                    if (shipped == null && inUse) prefs.scene = prefs.scenes.first()
                    dialog.dismiss(); renderTranslate()
                }
                .show()
        }
    }

    // ---- 本次会话 ------------------------------------------------------------------------------------------

    private var sessionQuery = ""
    /** What the list was last built from: a new sentence or a new query rebuilds it, the ticker alone does not. */
    private var sessionSig: Pair<Int, String>? = null
    private lateinit var sessionList: LinearLayout
    private lateinit var sessionScroll: android.widget.ScrollView

    /** The search box, 导出… and 清空; the list itself is [renderSession]'s. */
    private fun setupSession() {
        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sessionScroll = innerScroller().apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(sessionList, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        findViewById<FrameLayout>(R.id.sessionScrollBox).addView(sessionScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        findViewById<android.widget.EditText>(R.id.sessionSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                sessionQuery = s?.toString().orEmpty()
                sessionSig = null
                renderSession()
            }
        })
        findViewById<View>(R.id.sessionExport).setOnClickListener { shareSession() }
        findViewById<View>(R.id.sessionClear).setOnClickListener {
            CaptionService.clearHistory()
            sessionSig = null
            renderSession()
        }
        // a recreated activity restores the box's text by id before this runs: filter by what it actually shows
        sessionQuery = findViewById<android.widget.EditText>(R.id.sessionSearch).text?.toString().orEmpty()
    }

    /**
     * A scroller that lives inside the page's ScrollView: while it has room to move it keeps the drag, once it
     * reaches an end the page takes over. A plain nested ScrollView never sees the gesture — the page eats it.
     */
    private fun innerScroller() = object : android.widget.ScrollView(this) {
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1))
            return super.onTouchEvent(e)
        }
        override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            return super.onInterceptTouchEvent(e)
        }
    }

    /**
     * 本次会话: every finished sentence of this session, newest last, in a list that scrolls by itself instead of
     * dropping the oldest line whenever a new one arrives. New lines are followed while the reader is at the
     * bottom; scrolled up, the view stays where they put it. 导出 keeps every line, 清空 empties the lot.
     */
    private fun renderSession() {
        val all = CaptionService.history
        val q = sessionQuery.trim()
        val matched = if (q.isEmpty()) all else all.filter {
            it.zh.contains(q, ignoreCase = true) || it.ja?.contains(q, ignoreCase = true) == true
        }
        findViewById<TextView>(R.id.sessionCount).text = if (q.isEmpty()) "${all.size} 条" else "${matched.size} / ${all.size} 条"
        findViewById<View>(R.id.sessionExport).isEnabled = all.isNotEmpty()
        findViewById<View>(R.id.sessionClear).isEnabled = all.isNotEmpty()
        val empty = findViewById<TextView>(R.id.sessionEmpty)
        empty.text = if (all.isEmpty()) "开始字幕后，这里会一条条列出这次说过的句子。" else "没有匹配的句子。"
        empty.visibility = if (matched.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.sessionScrollBox).visibility = if (matched.isEmpty()) View.GONE else View.VISIBLE
        val sig = matched.size to q
        if (sessionSig == sig) return
        // was the reader looking at the newest line? then the rebuild should leave them there
        val atBottom = sessionList.childCount == 0 ||
            sessionScroll.scrollY + sessionScroll.height >= sessionList.height - 12
        sessionSig = sig
        sessionList.removeAllViews()
        // a long session would mean thousands of views; the newest few hundred are enough to read through, and
        // 导出 still carries all of them
        val shown = matched.takeLast(SESSION_MAX_ROWS)
        if (shown.size < matched.size) {
            sessionList.addView(TextView(this).apply {
                text = "…前面还有 ${matched.size - shown.size} 句，导出可以拿到全部"
                textSize = 12f; setTextColor(getColor(R.color.muted))
                setPadding(0, (6 * dpx).toInt(), 0, (4 * dpx).toInt())
            })
        }
        for (l in shown) {
            l.ja?.takeIf { it.isNotBlank() }?.let { ja ->
                sessionList.addView(TextView(this).apply {
                    text = ja; textSize = 12f; setTextColor(getColor(R.color.muted)); setLineSpacing(2 * dpx, 1f)
                })
            }
            sessionList.addView(TextView(this).apply {
                text = l.zh; textSize = 13.5f; setTextColor(getColor(R.color.text)); setLineSpacing(2 * dpx, 1f)
                setPadding(0, 0, 0, (8 * dpx).toInt())
            })
        }
        // the card grows with the list until that stops being reasonable, then the list scrolls inside it
        sizeSessionScroll(atBottom)
    }

    /**
     * Gives the transcript the height it needs, up to about 260 dp; a list measured while 首页 is hidden is 0 px
     * tall, so it is left alone rather than collapsed — [selectPage] re-sizes it when the page comes back.
     */
    private fun sizeSessionScroll(follow: Boolean) {
        sessionList.post {
            if (sessionList.height <= 0) return@post
            val max = (260 * dpx).toInt()
            val h = minOf(sessionList.height, max)
            if (sessionScroll.layoutParams.height != h) {
                sessionScroll.layoutParams = sessionScroll.layoutParams.apply { height = h }
                sessionScroll.requestLayout()
            }
            if (follow) sessionScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** 导出…: the whole session as plain text through the share sheet — no file to find and delete later. */
    private fun shareSession() {
        val all = CaptionService.history
        if (all.isEmpty()) return
        val text = all.joinToString("\n\n") { l -> l.ja?.takeIf { it.isNotBlank() }?.let { "$it\n${l.zh}" } ?: l.zh }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "字幕 $stamp")
        }
        startActivity(Intent.createChooser(send, "导出字幕"))
    }

    /** 翻译, where the LLM API is filled in. */
    override fun onResume() {
        super.onResume()
        loadStyle() // the overlay toolbar may have changed size / opacity / mode meanwhile (no listener while paused)
        renderLang()
        syncMode()
        prefs.listen(styleListener)
        renderPermissions()
        renderLogs()
        render()
        main.post(ticker)
        updater.resume()
        // back from the overlay permission page after tapping 开始字幕: carry on where that tap left off
        if (startPending) {
            startPending = false
            if (Settings.canDrawOverlays(this)) main.post { start() }
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.unlisten(styleListener)
        main.removeCallbacks(ticker)
        updater.pause()
    }

    override fun onDestroy() {
        // a manual check this recreation stops is redone as manual (also with automatic checks off). Decided here: a
        // stopped activity is recreated without saving its state again, and a process that ends forgets it.
        manualCheckStopped = isChangingConfigurations && updater.checkingManual
        updater.destroy()
        byokTest?.cancel(); byokTest = null
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            renderSession()
            // the translation switches are disabled while a session runs
            if (CaptionService.running != translateRunning) { renderTranslate(); renderLang() }
            // a session started or finished (its .part renamed): the list must not keep buttons for files that moved
            val now = SessionLog.list(getExternalFilesDir(null)!!).map { it.name } to SessionLog.activeFiles()
            if (now != listedLogs) renderLogs()
            main.postDelayed(this, 800)
        }
    }

    // ---- main card -----------------------------------------------------------------------------------------

    /** The last start was refused by the system (foreground service not allowed); kept until the next start tap. */
    private var startRefused = false
    /** 开始字幕 sent the user to the system's overlay page; the start continues when they come back. */
    private var startPending = false

    private fun render() {
        progress.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.isEnabled = true
        renderChips()
        if (CaptionService.running) {
            status(R.color.ok, "字幕正在显示", "回到你要看的视频或直播就行。\n点一下字幕，可以调大小或停止。")
            primary.text = "停止字幕"; primary.setIconResource(R.drawable.ic_stop)
            // magpie has no strong red fill: its danger look is red-soft with ink text and a red mark
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.err_soft))
            primary.setTextColor(getColor(R.color.text)); primary.iconTint = ColorStateList.valueOf(getColor(R.color.err))
            primary.setOnClickListener { CaptionService.stop(this); main.postDelayed({ render() }, 300) }
        } else {
            val missingPerm = !hasAudio() || !Settings.canDrawOverlays(this)
            val hint = if (missingPerm) "点下面的按钮，按提示允许下方两项，就能开始。" else "点下面的按钮，再在弹出的窗口里选你要看的 App。"
            when {
                // the button keeps saying 开始字幕 on a fresh install too: the tap goes to the page that fills in
                // what is missing instead of quietly doing nothing
                !prefs.byokReady -> status(R.color.warn, "先填翻译用的 LLM API", "服务地址和模型已经填好了，只需要粘贴你的 API Key，点「测试连接」就能开始。声音识别已经装在 App 里，不用下载。")
                startRefused -> status(R.color.err, "手机暂时不让字幕启动", "请再点一次开始。如果还是不行，请反馈出现这个提示前做了什么。")
                else -> status(R.color.brand, if (missingPerm) "还差一步" else "可以开始了", hint)
            }
            primary.text = "开始字幕"; primary.setIconResource(R.drawable.ic_play)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand))
            onPrimary(R.color.on_brand)
            primary.setOnClickListener { start() }
        }
    }

    /** Text and icon colour of the main button: each fill has its own readable foreground (indigo / red, day / night). */
    private fun onPrimary(color: Int) {
        primary.setTextColor(getColor(color)); primary.iconTint = ColorStateList.valueOf(getColor(color))
    }

    /** The models ship inside the APK; a build made without setup.sh has none, and the chip must say so. */
    private val engineReady by lazy {
        runCatching {
            assets.open("models/asr_sv/model.int8.onnx").close()
            assets.open("models/vad/silero_vad.onnx").close()
            true
        }.getOrDefault(false)
    }

    /** The three chips under the button: what listens, what translates, how the captions appear. */
    private fun renderChips() {
        chip(findViewById(R.id.chipEngine), if (engineReady) R.color.ok else R.color.warn,
            if (engineReady) "引擎 已就绪" else "引擎 未安装")
        val ready = prefs.byokReady
        // a long model id would push the third chip off a narrow phone; the page below shows it in full
        chip(findViewById(R.id.chipTranslate), if (ready) R.color.ok else R.color.warn,
            if (ready) "翻译 " + prefs.byokModel.take(14) else "翻译 未配置")
        chip(findViewById(R.id.chipMode), R.color.muted, if (prefs.finalOnly) "说完再出" else "边听边出")
    }

    /** One chip: a dot in [dot] in front of a single line of text. */
    private fun chip(v: TextView, dot: Int, text: String) {
        v.text = text
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize((7 * dpx).toInt(), (7 * dpx).toInt())
            setColor(getColor(dot))
        }
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
    }


    /** Busy state: no action, but keep the brand look (a disabled button is grey and hard to read). */
    private fun busy() {
        primary.isEnabled = true
        primary.setOnClickListener(null)
        primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand) and 0x00FFFFFF or 0xB3000000.toInt())
    }

    private fun status(color: Int, title: String, body: String) {
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(color)) }
        statusTitle.text = title
        statusBody.text = body
    }

    // ---- permissions ---------------------------------------------------------------------------------------

    private fun hasAudio() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun renderPermissions() {
        permList.removeAllViews()
        // the home page shows this card only while something required is missing; after that it is just noise
        findViewById<View>(R.id.permCard).visibility =
            if (hasAudio() && Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        permRow("① 听手机里播放的声音", "只听 App 播放出来的声音，不用麦克风", hasAudio(), permList) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_ONLY)
        }
        permRow("② 在屏幕上显示字幕", "让字幕浮在其他 App 上面", Settings.canDrawOverlays(this), permList) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        // optional: on the home card while it shows, and always in Settings (the home card hides once the two are granted)
        val notifBox = findViewById<LinearLayout>(R.id.notifList)
        notifBox.removeAllViews()
        findViewById<View>(R.id.notifSection).visibility = if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val ask = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM_ONLY) }
            permRow("通知（可以不开）", "在通知栏显示运行状态和「停止」按钮", granted, permList, ask)
            permRow("通知", "在通知栏显示运行状态和「停止」按钮", granted, notifBox, ask)
        }
    }


    private fun permRow(title: String, sub: String, granted: Boolean, parent: LinearLayout, grant: () -> Unit) {
        val dp = resources.displayMetrics.density
        if (parent.childCount > 0) parent.addView(divider())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text)) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(getColor(R.color.text2)) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (granted) {
            row.addView(TextView(this).apply { text = "✓ 已允许"; textSize = 14f; setTextColor(getColor(R.color.ok)); setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0) })
        } else {
            row.addView(pillButton("去允许") { grant() })
        }
        parent.addView(row)
    }

    // ---- translation / Jev ---------------------------------------------------------------------------------

    private val dpx get() = resources.displayMetrics.density

    /** Small grey caption above a control. */
    private fun label(t: String, top: Int = 14) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(getColor(R.color.text))
        setPadding(0, (top * dpx).toInt(), 0, (8 * dpx).toInt())
    }

    /**
     * Gives a bare EditText the look of a field: magpie's box, 10 dp corners, an indigo outline while it holds the
     * cursor. Without this it is indistinguishable from the text around it until it is tapped — on a phone that is
     * the difference between "here is a setting" and "here is something you can change".
     */
    private fun <T : android.widget.EditText> T.asField(): T = apply {
        setBackgroundResource(R.drawable.field_bg)
        setPadding((14 * dpx).toInt(), (12 * dpx).toInt(), (14 * dpx).toInt(), (12 * dpx).toInt())
        minimumHeight = (52 * dpx).toInt()
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Heading of a group inside a settings card. */
    private fun heading(t: String, top: Int = 26) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(getColor(R.color.text)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, (top * dpx).toInt(), 0, (4 * dpx).toInt())
    }

    /** One or two lines saying what an option does (text2: these carry privacy / cost explanations, so ≥ 4.5:1). */
    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(getColor(R.color.text2)); setLineSpacing(2 * dpx, 1f)
        setPadding(0, (6 * dpx).toInt(), 0, 0)
    }

    /**
     * A long explanation folded to two lines, with 展开 under it. The 翻译 page carries several paragraphs of
     * why-and-what; unfolded they push the controls that actually get touched off the first screens.
     */
    private fun foldHint(t: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val body = hint(t).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        addView(body)
        var open = false
        addView(TextView(this@MainActivity).apply {
            text = "展开说明"
            textSize = 13f
            setTextColor(getColor(R.color.brand))
            setPadding(0, (6 * dpx).toInt(), 0, 0)
            minimumHeight = (40 * dpx).toInt()
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                open = !open
                text = if (open) "收起" else "展开说明"
                body.maxLines = if (open) Int.MAX_VALUE else 2
                body.ellipsize = if (open) null else android.text.TextUtils.TruncateAt.END
            }
        })
    }

    /** A risk / cost / "takes effect later" note: magpie's amber note, never cut short. */
    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(getColor(R.color.text2)); setLineSpacing(2 * dpx, 1f)
        setBackgroundResource(R.drawable.note_warn)
        setPadding((10 * dpx).toInt(), (8 * dpx).toInt(), (10 * dpx).toInt(), (8 * dpx).toInt())
    }

    /** The hairline between two rows of a list. */
    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.line2))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dpx).toInt().coerceAtLeast(1)).apply { marginStart = (14 * dpx).toInt() }
    }

    /** A small grey button at the end of a row (magpie's .text.action on a pill). */
    private fun pillButton(t: String, onClick: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = t; textSize = 14f; setTextColor(getColor(R.color.text)); minHeight = (48 * dpx).toInt(); minimumHeight = minHeight
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.pill))
        setOnClickListener { onClick() }
    }

    /** Group title + white list card on the settings page; returns the card's content box. */
    private fun group(root: LinearLayout, title: String): LinearLayout {
        root.addView(TextView(this, null, 0, R.style.SectionTitle).apply { text = title }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (20 * dpx).toInt(); bottomMargin = (6 * dpx).toInt()
        })
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dpx).toInt(), (6 * dpx).toInt(), (14 * dpx).toInt(), (14 * dpx).toInt())
        }
        root.addView(com.google.android.material.card.MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.card)); radius = 12 * dpx; cardElevation = 0f
            strokeColor = getColor(R.color.line); strokeWidth = (1 * dpx).toInt().coerceAtLeast(1)
            addView(inner)
        })
        return inner
    }

    /**
     * A switch with its title, and the description under it. [fold] keeps a long description to two lines with
     * 展开 under it — the 记录 page has two paragraphs that otherwise bury the switches they belong to.
     */
    private fun switchRow(box: LinearLayout, title: String, desc: String, on: Boolean,
                          fold: Boolean = false, onChange: (Boolean) -> Unit): MaterialSwitch {
        val s = MaterialSwitch(this).apply {
            text = title; textSize = 15f; setTextColor(getColor(R.color.text)); isChecked = on
            minHeight = (48 * dpx).toInt()
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        box.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dpx).toInt()
        })
        val body: View = if (fold) foldHint(desc) else hint(desc)
        body.setPadding(0, 0, (56 * dpx).toInt(), 0)
        box.addView(body)
        return s
    }

    /** A dropdown: magpie's grey pill with a chevron; full width unless placed at the end of a row. With [wrap] the
     *  shown value may take several lines instead of being cut (a row that shares its width with a name). */
    private fun dropdown(items: List<String>, sel: Int, enabled: Boolean, wrap: Boolean = false, onPick: (Int) -> Unit) = android.widget.Spinner(this).apply {
        adapter = object : android.widget.ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(p: Int, c: View?, g: android.view.ViewGroup) =
                (super.getView(p, c, g) as TextView).apply {
                    textSize = 14f; setTextColor(getColor(R.color.text)); minHeight = (48 * dpx).toInt()
                    setPadding((12 * dpx).toInt(), (6 * dpx).toInt(), (8 * dpx).toInt(), (6 * dpx).toInt())
                    if (wrap) {
                        // the platform item has a fixed list-item height: let a wrapped value grow it
                        isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
                        layoutParams = (layoutParams ?: android.view.ViewGroup.LayoutParams(0, 0)).apply {
                            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT; height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    }
                }
            override fun getDropDownView(p: Int, c: View?, g: android.view.ViewGroup) =
                (super.getDropDownView(p, c, g) as TextView).apply { textSize = 15f; minHeight = (48 * dpx).toInt() }
        }
        setBackgroundResource(R.drawable.spinner_pill)
        setPadding(0, 0, (26 * dpx).toInt(), 0) // room for the chevron
        setSelection(sel, false)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        isEnabled = enabled; alpha = if (enabled) 1f else 0.5f
        minimumHeight = (48 * dpx).toInt()
    }


    /** A segment of [choice], recoloured to magpie's .seg: an unchosen option is plain text on the grey track (no
     *  fill, no outline); the chosen one is the card-coloured thumb. No stroke at all: the group pulls each button
     *  over its neighbour by the stroke width on every measure, which let the thumb cover the next option's edge.
     *  The group also squares the inner corners on every measure; every segment keeps 8dp. */
    private class SegButton(ctx: android.content.Context) : MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) {
        init { strokeWidth = 0 }
        override fun setShapeAppearanceModel(m: com.google.android.material.shape.ShapeAppearanceModel) =
            super.setShapeAppearanceModel(m.toBuilder().setAllCornerSizes(8 * resources.displayMetrics.density).build())
    }

    /** Single-choice row of segmented buttons (magpie's .seg: a grey track, the chosen option a raised thumb). */
    private fun <T> choice(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) =
        com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            // a LinearLayout lines its children up on the text baseline: a button whose label shrank would sit lower
            isBaselineAligned = false
            setBackgroundResource(R.drawable.seg_track)
            setPadding((3 * dpx).toInt(), 0, (3 * dpx).toInt(), 0)
            options.forEach { (v, t) ->
                val b = SegButton(this@MainActivity).apply {
                    id = View.generateViewId(); text = t; tag = v; textSize = 14f; minHeight = (48 * dpx).toInt()
                    // narrow phones: the label shrinks (down to 10 sp) until the laid-out text is uncut, instead of
                    // being cut to "在…"; judged on the real layout, so the button's paddings and stroke count. Still
                    // cut at 10 sp (very large system font): it wraps over as many lines as it needs, never cut.
                    isAllCaps = false
                    setPadding((4 * dpx).toInt(), paddingTop, (4 * dpx).toInt(), paddingBottom)
                    val min = 10 * resources.displayMetrics.scaledDensity
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val b = v as MaterialButton
                        val lay = b.layout ?: return@addOnLayoutChangeListener
                        val cut = lay.lineCount > b.maxLines || (0 until lay.lineCount).any { lay.getEllipsisCount(it) > 0 }
                        if (!cut) return@addOnLayoutChangeListener
                        if (b.textSize > min) b.post {
                            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, maxOf(min, b.textSize - resources.displayMetrics.scaledDensity / 2))
                        } else b.post { b.maxLines = Int.MAX_VALUE; b.ellipsize = null }
                    }
                }
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                // the group's addView sets one line and an ellipsis on every button: anything set before is lost
                b.maxLines = 1; b.ellipsize = android.text.TextUtils.TruncateAt.END
                if (v == current) check(b.id)
            }
            addOnButtonCheckedListener { g, id, checked ->
                @Suppress("UNCHECKED_CAST") if (checked) onPick(g.findViewById<View>(id).tag as T)
            }
        }

    /**
     * The translation service, scene, display mode and Jev. While running: the two Jev switches wait for the next start,
     * the display mode applies at once, everything else from the next sentence (CaptionService rebuilds its setup).
     */
    private fun renderTranslate() {
        val root = findViewById<LinearLayout>(R.id.translateList)
        root.removeAllViews()
        val running = CaptionService.running
        translateRunning = running
        if (running) root.addView(note("字幕正在运行：内容类型、结合上文、去掉重复的字、LLM API（切换配置或点保存后）和 Jev 在线模型服务、key 改了从下一句开始生效；智能断句和检查译文从下次开始字幕时生效；显示方式马上生效。"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dpx).toInt() })

        // -- the LLM API the captions are translated with
        var box = group(root, "LLM API")
        renderByok(box, running)

        box = group(root, "内容")
        box.addView(label("内容类型"))
        // the scenes: the built-ins and the user's own; 编辑 the one in use, 新建 another (a running session: from the
        // next sentence)
        val scenes = prefs.scenes
        val scene = prefs.scene
        box.addView(dropdown(scenes.map { it.label }, scenes.indexOfFirst { it.id == scene.id }.coerceAtLeast(0), true) {
            if (scenes[it].id != prefs.scene.id) { prefs.scene = scenes[it]; main.post { renderTranslate() } }
        }.apply { contentDescription = "内容类型" })
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            addView(pillButton("编辑") { editScene(scene) })
            addView(pillButton("新建") { editScene(null) }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dpx).toInt() })
        box.addView(hint(scene.summary(prefs.targetLang)))

        // translation context: on/off and how many finished sentences (a running session: from the next sentence)
        val ctxCount = label("")
        val ctxSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 1f; valueTo = Pipeline.MAX_PREVIOUS_SENTENCES.toFloat(); stepSize = 1f; isTickVisible = false
            value = prefs.contextSentences.toFloat()
        }
        fun showCtx() {
            ctxCount.text = "参考前面 ${prefs.contextSentences} 句"
            ctxCount.visibility = if (prefs.contextOn) View.VISIBLE else View.GONE
            ctxSlider.visibility = ctxCount.visibility
        }
        switchRow(box, "结合上文来翻译", "翻译每句话时，把前面刚说过的几句一起交给翻译参考，帮助它理解人称、指代和省略的内容。参考的句子越多，翻译要处理的文字越多，可能更慢。",
            prefs.contextOn) { prefs.contextOn = it; showCtx() }
        box.addView(ctxCount)
        box.addView(ctxSlider)
        ctxSlider.addOnChangeListener { _, v, user -> if (user) { prefs.contextSentences = v.toInt(); showCtx() } }
        showCtx()

        // -- DIY: manual segmentation
        box = group(root, "断句")
        val segPause = label("")
        val segMax = label("")
        // Slider 要求 value 必须等于 valueFrom + n×stepSize，否则布局时抛 IllegalStateException（会闪退）。
        // 所以刻度用毫秒整数，并把存下来的值先吸附到最近的刻度。
        fun snapTo(v: Int, from: Int, to: Int, step: Int) =
            (from + Math.round((v - from).toFloat() / step) * step).coerceIn(from, to)
        val pauseSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 100f; valueTo = 1_200f; stepSize = 50f; isTickVisible = false
            value = snapTo(prefs.endSilenceMs, 100, 1_200, 50).toFloat()
        }
        val maxSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 1_500f; valueTo = 12_000f; stepSize = 250f; isTickVisible = false
            value = snapTo(prefs.maxUtteranceMs, 1_500, 12_000, 250).toFloat()
        }
        fun showSeg() {
            segPause.text = "停顿切段 %.2f 秒".format(prefs.endSilenceMs / 1000f)
            segMax.text = "单句最长 %.2f 秒".format(prefs.maxUtteranceMs / 1000f)
        }
        pauseSlider.addOnChangeListener { _, v, user -> if (user) { prefs.setSegmentation(v.toInt(), prefs.maxUtteranceMs); showSeg() } }
        maxSlider.addOnChangeListener { _, v, user -> if (user) { prefs.setSegmentation(prefs.endSilenceMs, v.toInt()); showSeg() } }
        showSeg()
        box.addView(segPause); box.addView(pauseSlider)
        box.addView(segMax); box.addView(maxSlider)
        box.addView(foldHint("停顿多久算一句话说完了、一句话最长多长。调小出字更快但句子更碎（太碎识别会变差）；调大句子更完整但更晚出现。下次开始字幕时生效。"))

        // -- how captions appear
        box = group(root, "字幕怎么出现")
        modeGroup = choice(listOf(false to "边听边出", true to "说完再出"), prefs.finalOnly) { if (prefs.finalOnly != it) prefs.finalOnly = it }
        box.addView(modeGroup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dpx).toInt()
        })
        box.addView(foldHint("「边听边出」：一边说一边显示，灰色的字还会修改，说完一句后变成正式字幕。「说完再出」：每句话说完才显示，画面更干净，但会晚一点。字幕上的工具栏也能随时切换。"))
        switchRow(box, "去掉重复的字", "一句话分段识别时，接口处偶尔会把同一段话识别两遍。打开后自动去掉多出来的那一遍；如果说话人真的重复说了，也可能被去掉。",
            prefs.dedupeJoin) { prefs.dedupeJoin = it }

        // -- 译文检查（用你自己的 LLM API；Jev 已彻底移除）
        box = group(root, "译文检查（可选，需要联网）")
        box.addView(hint("建议只在用 qwen-mt 这类「单句翻译」模型时才打开：它看不到上下文，容易把否定或转折翻反、把比喻按字面直译，逐句复检能兜住一部分。用 qwen-flash 这类通用模型时通常不必开——每句会多一次请求，延迟约 +0.4 秒，费用也会跟着涨。"))
        val checkSwitch = switchRow(box, "检查译文", "每句译文出来后由模型再看一遍：有没有把意思译反、漏了否定、把比喻按字面直译。发现问题就带着提示重新翻译这一句。", prefs.jevCheck) {}
        val checkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(checkBox)
        fun renderCheck() {
            checkBox.removeAllViews()
            checkBox.visibility = if (prefs.jevCheck) View.VISIBLE else View.GONE
            if (!prefs.jevCheck) return
            checkBox.addView(label("校验模型"))
            val modelInput = android.widget.EditText(this).asField().apply {
                hint = "例如 qwen-flash"
                isSingleLine = true; textSize = 16f; setText(prefs.judgeModelSetting)
                setTextColor(getColor(R.color.text)); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            checkBox.addView(modelInput)
            checkBox.addView(label("校验地址（留空则用翻译那套）"))
            val urlInput = android.widget.EditText(this).asField().apply {
                hint = "例如 https://dashscope.aliyuncs.com/compatible-mode/v1"
                isSingleLine = true; textSize = 15f; setText(prefs.judgeUrlSetting)
                setTextColor(getColor(R.color.text)); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            checkBox.addView(urlInput)
            checkBox.addView(label(if (prefs.hasJudgeKey) "校验 API Key：已保存（留空则用翻译的 key）" else "校验 API Key（留空则用翻译的 key）"))
            val keyInput = android.widget.EditText(this).asField().apply {
                hint = if (prefs.hasJudgeKey) "输入新的 key 来替换" else "在这里粘贴 key"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true; textSize = 15f; setTextColor(getColor(R.color.text))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            checkBox.addView(keyInput)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "清除 key"; textSize = 16f; isEnabled = prefs.hasJudgeKey
                setOnClickListener { prefs.setJudgeKey(null); renderCheck() }
            })
            row.addView(MaterialButton(this).apply {
                text = "保存"; textSize = 16f
                setOnClickListener {
                    prefs.judgeModelSetting = modelInput.text.toString()
                    prefs.judgeUrlSetting = urlInput.text.toString()
                    val k = keyInput.text.toString().trim()
                    if (k.isNotEmpty()) prefs.setJudgeKey(k)
                    Toast.makeText(this@MainActivity, "已保存，下次开始字幕时生效", Toast.LENGTH_SHORT).show()
                    renderCheck()
                }
            })
            checkBox.addView(row)
            checkBox.addView(hint("现在用「${prefs.judgeModel}」逐句检查；" +
                if (prefs.judgeUrl.isEmpty() && !prefs.hasJudgeKey) "地址和 key 都复用翻译那套。"
                else "地址和 key 用上面单独填的，留空的那项才复用翻译那套。"))
            checkBox.addView(hint("只发送识别出的文字和译文，不发送声音。"))
        }
        checkSwitch.setOnCheckedChangeListener { _, on -> prefs.jevCheck = on; renderCheck() }
        renderCheck()
    }

    /** The running BYOK test, so a second tap, a new render or leaving the screen does not show a stale result. */
    @Volatile private var byokTest: ByokTranslator? = null

    /** BYOK: the user's OpenAI-compatible service; URL, key, model id and a test that also finds the thinking switch. */
    private fun renderByok(box: LinearLayout, running: Boolean) {
        val testing = byokTest != null
        val profiles = prefs.byokProfiles
        val active = prefs.byokProfile
        box.addView(label("配置"))
        box.addView(dropdown(profiles.map { it.name }, profiles.indexOfFirst { it.id == active.id }.coerceAtLeast(0), !testing) {
            if (profiles[it].id != prefs.byokProfile.id) {
                prefs.useByokProfile(profiles[it]); byokDraft = null
                byokResult = "已切换到「${profiles[it].name}」，字幕从下一句开始用这个配置。".takeIf { running && prefs.byokReady }
                main.post { renderTranslate() }
            }
        }.apply { contentDescription = "配置" })
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            addView(pillButton("重命名") { askProfileName(active, active.name, rename = true) }.apply { isEnabled = !testing })
            addView(pillButton("新建") { askProfileName(null, "", rename = false) }.apply {
                isEnabled = !testing && profiles.size < LlmProfile.MAX_PROFILES
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
            if (profiles.size > 1) addView(pillButton("删除") {
                confirmDelete("删除「${active.name}」？", "这个配置里的服务地址、模型 ID、API Key 和高级设置都会删掉。") {
                    prefs.deleteByokProfile(active); byokDraft = null
                    byokResult = "已切换到「${prefs.byokProfile.name}」，字幕从下一句开始用这个配置。".takeIf { running && prefs.byokReady }
                    renderTranslate()
                }
            }.apply {
                isEnabled = !testing; setTextColor(getColor(R.color.err))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dpx).toInt() })
        box.addView(foldHint("填一个 OpenAI 兼容接口的 LLM 服务来翻译字幕，只发送识别出的文字，不发送声音。\n模型要求：必须能关闭思考（深度思考 / reasoning），否则每句都要等很久；尽量选回复速度快的模型，字幕才跟得上。"))
        fun field(title: String, value: String, hintText: String, password: Boolean = false): android.widget.EditText {
            box.addView(label(title))
            return android.widget.EditText(this).asField().apply {
                setText(value); hint = hintText; isSingleLine = true; textSize = 16f; setTextColor(getColor(R.color.text))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                box.addView(this)
            }
        }
        val d = byokDraft ?: ByokDraft(prefs.byokUrl, prefs.byokModel).also { byokDraft = it }
        val url = field("服务地址（OpenAI 兼容）", d.url, "例如 https://api.deepseek.com，结尾有没有 /v1 都可以")
        val model = field("模型 ID", d.model, "例如 deepseek-chat")
        val saved = prefs.byokKey?.takeUnless { d.clearKey }
        val key = field(when {
            saved != null -> "API Key：已保存（…${saved.takeLast(4)}）"
            d.clearKey -> "API Key：保存后清除（不需要 key 的服务可以空着）"
            else -> "API Key：没填（不需要 key 的服务可以空着）"
        }, d.key, if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key", password = true)
        // kept in the draft as typed, so re-rendering (any setting on this page) does not lose it
        fun keep() { d.url = url.text.toString(); d.model = model.text.toString(); d.key = key.text.toString() }
        for (f in listOf(url, model, key)) f.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) = keep()
        })
        val result = hint("")
        // save what is in the boxes; null when something is wrong (and says what)
        fun save(): String? {
            val u = url.text.toString().trim(); val m = model.text.toString().trim(); val k = key.text.toString().trim()
            val (bad, _) = ByokTranslator.chatUrl(u)
            when {
                bad != null -> { result.text = "⚠ $bad"; return null }
                m.isEmpty() -> { result.text = "⚠ 还没填模型 ID"; return null }
                m.any { it.isWhitespace() } -> { result.text = "⚠ 模型 ID 里不要有空格"; return null }
                k.isNotEmpty() && !Jev.validKey(k) -> { result.text = "⚠ key 里有空格、换行或特殊字符，请重新复制"; return null }
            }
            // one edit; a running session switches to the saved service from the next sentence, as one change
            prefs.saveByok(u, m, k.ifEmpty { if (d.clearKey) "" else null }, d.userAgent, d.headers)
            byokDraft = null; key.setText("")
            return ByokTranslator.chatUrl(u).second
        }
        // while a test runs the boxes are locked, so its result always belongs to what they show
        // Running: "保存" switches the session to what was saved from the next sentence (it tests a new model itself),
        // so the test button is off (two probes would bill twice) and so is clearing the key (the session needs one).
        listOf(url, model, key).forEach { it.isEnabled = !testing }
        // on a line of its own: four buttons do not fit a phone's width
        val extra = listOfNotNull((d.userAgent ?: prefs.byokUserAgent).ifEmpty { null }?.let { "自定义 User-Agent" },
            (d.headers ?: prefs.byokHeaders).size.takeIf { it > 0 }?.let { "$it 个请求头" },
            "未保存".takeIf { d.userAgent != null || d.headers != null }).joinToString("、")
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "高级设置" + if (extra.isEmpty()) "（User-Agent、请求头）" else "（$extra）"
            textSize = 15f; isEnabled = !testing; isAllCaps = false
            setOnClickListener { editByokAdvanced(d) }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            // a draft change like the rest: saved (and a running session switched) only by 保存
            text = "清除 key"; textSize = 16f; isEnabled = saved != null && !testing
            setOnClickListener { d.clearKey = true; d.key = ""; renderTranslate() }
        })
        row.addView(MaterialButton(this).apply {
            text = "保存"; textSize = 15f; isEnabled = !testing
            minimumHeight = (48 * dpx).toInt()
            setOnClickListener {
                if (save() != null) {
                    if (running) byokResult = "已保存，字幕从下一句开始用新的设置；第一次用这个模型时会先自动测试怎样关闭思考。"
                    renderTranslate()
                }
            }
        })
        // 保存 is the commit; the test is the secondary one, so it is the outlined button here
        val test = MaterialButton(this, null, R.style.Widget_JpSub_ActionButton).apply {
            text = if (testing) "测试中…" else "测试连接"; textSize = 15f; isEnabled = !running && !testing
        }
        row.addView(test)
        box.addView(row)
        box.addView(result)
        val chat = ByokTranslator.chatUrl(prefs.byokUrl).second
        val known = chat?.let { prefs.byokVariant(it, prefs.byokModel) }
        result.text = byokResult?.also { byokResult = null } ?: when {
            testing -> "正在依次尝试关闭思考的参数（最多 6 次，每次最多 8 秒，每次都会计费）…"
            chat == null || prefs.byokModel.isEmpty() -> "填好地址、模型 ID 和 key 后点「测试连接」。第一次使用时会依次试最多 6 种关闭思考的参数（每种是一次计费请求），找到没检测到思考的就记住。"
            known == ByokTranslator.NONE_WORKED -> "⚠ 上次测试：已知的参数都关不掉这个模型的思考，翻译可能很慢。仍然可以使用。"
            known != null -> "上次测试可用（用的参数：${ByokTranslator.variant(known)?.label}，那次没检测到思考）。实际请求地址：$chat"
            else -> "还没测试，第一次开始字幕时会自动测。实际请求地址：$chat"
        }
        test.setOnClickListener {
            val chatUrl = save() ?: return@setOnClickListener
            val m = prefs.byokModel; val service = prefs.byokService() ?: return@setOnClickListener
            val t = ByokTranslator(service, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null,
                maxTokens = mtMaxTokens(prefs.targetLang))
            byokTest?.cancel(); byokTest = t
            renderTranslate() // shows "testing" and locks the boxes
            Thread {
                val steps = mutableListOf<ByokTranslator.Step>()
                val v = t.probe(steps)
                main.post {
                    if (byokTest !== t || isDestroyed) return@post // cancelled (captions started), or the screen is gone
                    byokTest = null
                    val ok = steps.firstOrNull { it.variant === v }?.result
                    val none = v == null && t.exhausted(steps)
                    when {
                        v != null -> prefs.setByokVariant(chatUrl, m, v.name)
                        none -> prefs.setByokVariant(chatUrl, m, ByokTranslator.NONE_WORKED)
                    }
                    // shown once by the next render
                    byokResult = when {
                        ok != null -> "✓ 可以用。参数：${v!!.label}（这次没检测到思考，已记住）；收到第一个字 ${ok.firstContentMs ?: "?"} ms" +
                            (if ((ok.firstContentMs ?: 0) > FIRST_CONTENT_MS) "（超过 ${FIRST_CONTENT_MS / 1000} 秒，翻译时这句会判为失败）" else "") + "。\n测试译文：${ok.text.take(60)}"
                        none -> "⚠ 连得上，但试过的 ${steps.size} 种参数都没能关掉思考，翻译可能很慢或经常失败。仍然可以使用。"
                        else -> "✗ 不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}"
                    }
                    renderTranslate()
                }
            }.start()
        }
    }
    /** The finished test's outcome, shown once by the next render. */
    private var byokResult: String? = null
    /**
     * What the LLM API boxes hold but 保存 has not saved yet, so a re-render (高级设置 closing, a test) keeps it:
     * address, model, a typed key, "clear the saved key", and the 高级设置 (null: unchanged). All of it is saved by one
     * 保存, so a running session never pairs one service's headers or key with another's address.
     */
    private class ByokDraft(var url: String, var model: String) {
        var key = ""; var clearKey = false
        var userAgent: String? = null; var headers: List<Pair<String, String>>? = null
    }
    private var byokDraft: ByokDraft? = null

    /**
     * 高级设置 of the LLM API: the User-Agent (empty = ours) and extra request headers, one row of two boxes each. 好
     * checks them and puts them in [draft]; the LLM API's 保存 saves them with the rest of the service (the headers
     * sealed like the key), and a running session takes them from the next sentence.
     */
    private fun editByokAdvanced(draft: ByokDraft) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dpx).toInt(), (4 * dpx).toInt(), (22 * dpx).toInt(), 0)
        }
        fun box(v: String, h: String) = android.widget.EditText(this).asField().apply {
            setText(v); hint = h; isSingleLine = true; textSize = 15f; setTextColor(getColor(R.color.text))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val ua = box(draft.userAgent ?: prefs.byokUserAgent, ByokTranslator.defaultUserAgent)
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun rows() = (0 until rowsBox.childCount).map {
            val r = rowsBox.getChildAt(it) as LinearLayout
            (r.getChildAt(0) as android.widget.EditText).text.toString().trim() to (r.getChildAt(2) as android.widget.EditText).text.toString().trim()
        }
        fun addRow(k: String, v: String) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(box(k, "名字"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = ":"; textSize = 15f; setTextColor(getColor(R.color.muted)); setPadding((6 * dpx).toInt(), 0, (6 * dpx).toInt(), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(box(v, "值"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "×"; textSize = 18f; setTextColor(getColor(R.color.text2)); minWidth = 0; minimumWidth = 0
                setPadding((10 * dpx).toInt(), 0, (10 * dpx).toInt(), 0); contentDescription = "删掉这个请求头"
                setOnClickListener { rowsBox.removeView(row) }
            })
            rowsBox.addView(row)
        }
        (draft.headers ?: prefs.byokHeaders).forEach { addRow(it.first, it.second) }
        if (rowsBox.childCount == 0) addRow("", "")
        val status = hint("")
        form.addView(label("User-Agent", 8)); form.addView(ua)
        form.addView(hint("空着就用 ${ByokTranslator.defaultUserAgent}。有的服务会拦下不认识的 User-Agent，例如 B 站 Index 的免费接口要求填 Index-Translate-Client/1.0。"))
        form.addView(label("自定义请求头"))
        form.addView(hint("左边写名字，右边写值，每个请求都会带上。值和 key 一样加密保存，使用记录里只记名字。"))
        form.addView(rowsBox)
        form.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "+ 加一个"; textSize = 14f
            setOnClickListener { if (rowsBox.childCount < ByokService.MAX_HEADERS) addRow("", "") }
        })
        form.addView(status)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("LLM API 高级设置")
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setPositiveButton("好", null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val agent = ua.text.toString().trim()
            val headers = rows().filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
            val err = ByokService.badAdvanced(agent, headers)
            if (err != null) { status.text = "⚠ $err"; return@setOnClickListener }
            // into the draft: saved with the address, model and key by the LLM API's 保存
            draft.userAgent = agent; draft.headers = headers
            byokResult = "高级设置改好了，点「保存」才会生效。"
            dialog.dismiss(); renderTranslate()
        }
    }

    // ---- session logs --------------------------------------------------------------------------------------

    private fun renderLogs() {
        val box = findViewById<LinearLayout>(R.id.logList)
        box.removeAllViews()
        val root = getExternalFilesDir(null)!!
        // a killed session leaves a headerless recording: finish it here, so the list never offers an unplayable file
        AudioRecorder.recover(SessionLog.dir(root))
        val files = SessionLog.list(root)
        val active = SessionLog.activeFiles()
        listedLogs = files.map { it.name } to active
        // off by default; the switch takes effect from the next start (a running session keeps what it began with)
        switchRow(box, "记录每次使用", "打开后，每次开始字幕会在手机上留一份文字记录：识别结果、翻译的提示词和译文、在线 Decisions 模型辅助的请求和回答，不含 key；声音默认不记录，需要的话在下面单独打开。只有你点「分享」才会离开手机，出问题时可以发给开发者帮助排查。从下次开始字幕起生效。",
            prefs.keepLogs, fold = true) { prefs.keepLogs = it; renderLogs() }
        // a recording is only kept for a session that writes a log: this switch does nothing while 记录每次使用 is off
        switchRow(box, "同时保留音频", "默认关闭；只在上面「记录每次使用」打开时才生效。把每次字幕采集到的声音和文字记录一起存成录音文件，只保存在本机、不会上传，每分钟约 1.9 MB；总量超过 200 MB 时自动删除最旧的完整录音。从下次开始字幕起生效。",
            prefs.recordAudio, fold = true) { prefs.recordAudio = it }
        if (files.isEmpty()) {
            box.addView(TextView(this).apply {
                text = if (prefs.keepLogs) "还没有记录。" else "还没有记录。现在不记录，打开上面的开关后才会记录。"
                textSize = 15f; setTextColor(getColor(R.color.text2))
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            })
            return
        }
        val dp = resources.displayMetrics.density
        // all logs stay reachable: beyond 5 the list scrolls inside a fixed-height box instead of growing the page
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        files.forEachIndexed { i, f ->
            // a hairline between records: without one three rows read as one block, and a thumb lands on the
            // wrong record's 删除
            if (i > 0) rows.addView(View(this).apply { setBackgroundColor(getColor(R.color.line2)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (68 * dp).toInt()
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // session-20261010-151200[-1].jsonl[.part] → 2026-10-10 15:12:00
            val n = f.name.removePrefix("session-").substringBefore(".jsonl")
            val title = if (n.length >= 15) "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)} ${n.substring(9, 11)}:${n.substring(11, 13)}:${n.substring(13, 15)}${n.substring(15)}" else f.name
            texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(getColor(R.color.text)) })
            val complete = SessionLog.complete(f)
            // the recording belongs to the record: its size is shown next to the log's (null: no audio was kept)
            val audio = SessionFiles.audioOf(f)
            texts.addView(TextView(this).apply {
                // .part: still running, or the session failed / was killed before the log was finished
                text = "%.1f KB".format(f.length() / 1024.0) +
                    (audio?.let { " · 音频 " + sizeText(it.length()) } ?: "") + when {
                    complete -> ""
                    f in active -> " · 正在记录，结束后可以分享"
                    else -> " · 没有正常结束"
                }
                textSize = 13.5f; setTextColor(getColor(R.color.text2))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // both are outlined buttons with a hairline: the theme turns materialButtonOutlinedStyle into the
            // borderless segmented look, which is what made them read as plain text
            row.addView(MaterialButton(this, null, R.style.Widget_JpSub_ActionButton).apply {
                text = "分享"; isEnabled = f !in active
                minimumHeight = (48 * dp).toInt()
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                setOnClickListener { share(f) }
            })
            row.addView(MaterialButton(this, null, R.style.Widget_JpSub_ActionButton).apply {
                text = "删除"; setTextColor(getColor(R.color.err)); isEnabled = f !in active
                minimumHeight = (48 * dp).toInt()
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                setOnClickListener { confirmDelete("删除 $title 的记录？", "这条记录如果有音频，会一起删除。删除后不能恢复。") {
                    if (!SessionFiles.deleteLog(f)) Toast.makeText(this@MainActivity, "删除失败：记录正在写入或文件被占用", Toast.LENGTH_SHORT).show()
                    renderLogs()
                } }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * dp).toInt()
            })
            rows.addView(row)
        }
        if (files.size > LOGS_BEFORE_SCROLL) {
            val scroller = object : android.widget.ScrollView(this) {
                // the page is a ScrollView too: keep the gesture in this list while it can scroll
                override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                    parent.requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1))
                    return super.onTouchEvent(e)
                }
                override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                    return super.onInterceptTouchEvent(e)
                }
            }.apply { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; addView(rows) }
            // about 5½ rows: the cut-off row shows there is more
            box.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (LOGS_BEFORE_SCROLL * 58 * dp + 28 * dp).toInt()))
        } else box.addView(rows)
        val deletable = files.count { it !in active }
        // a hairline above the totals, so 全部清空 is not mistaken for part of the last record
        box.addView(View(this).apply { setBackgroundColor(getColor(R.color.line2)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)).apply {
                topMargin = (6 * dp).toInt()
            })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
            addView(TextView(this@MainActivity).apply {
                // the total counts a record's log and recording together (core counts the bytes, the text is ours)
                text = "共 ${files.size} 个，" + sizeText(SessionFiles.totalBytes(root))
                textSize = 12.5f; setTextColor(getColor(R.color.text2))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialButton(this@MainActivity, null, R.style.Widget_JpSub_ActionButton).apply {
                text = "全部清空"; setTextColor(getColor(R.color.err)); isEnabled = deletable > 0
                minimumHeight = (40 * dp).toInt()
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                setOnClickListener { confirmDelete("删除全部 $deletable 个记录？", "正在记录的不会删除；音频会跟着它的记录一起删除。删除后不能恢复。") {
                    val left = SessionFiles.deleteAll(root)
                    if (left > 0) Toast.makeText(this@MainActivity, "还剩 $left 个（正在记录或无法删除）", Toast.LENGTH_SHORT).show()
                    renderLogs()
                } }
            })
        })
    }

    private fun confirmDelete(title: String, msg: String, ok: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> ok() }
            .show()
    }

    private var listedLogs: Pair<List<String>, Set<java.io.File>>? = null
    private var translateRunning = false

    /** "999.9 KB" / "49.3 MB": the log list's own size text (core counts bytes, formatting stays here). */
    private fun sizeText(bytes: Long) = if (bytes < 1048576) "%.1f KB".format(bytes / 1024.0) else "%.1f MB".format(bytes / 1048576.0)

    private fun share(f: java.io.File) {
        // the list may be up to one tick old: never hand out a URI for a file that is being written or no longer exists
        if (SessionLog.isActive(f) || !f.isFile) {
            Toast.makeText(this, "这个记录刚刚结束或已变动，请重新选择", Toast.LENGTH_SHORT).show(); renderLogs(); return
        }
        fun uriOf(file: java.io.File) = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.logs", file)
        // the recording belongs to the record: with one kept, both files go together; without, the single share stays as it was
        val audio = SessionFiles.audioOf(f)?.takeIf { it.isFile }
        val send = if (audio == null) Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream" // TG and file managers keep .jsonl as a file instead of pasting text
            putExtra(Intent.EXTRA_STREAM, uriOf(f))
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream" // same as above: .jsonl and .wav both stay files
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uriOf(f), uriOf(audio)))
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享 ${f.name}"))
    }

    // ---- subtitle style ------------------------------------------------------------------------------------

    private fun setupStyle() {
        preview = CaptionOverlayView(this, interactive = false)
        findViewById<FrameLayout>(R.id.previewHolder).addView(preview)
        val size = findViewById<Slider>(R.id.sliderSize)
        val alpha = findViewById<Slider>(R.id.sliderAlpha)
        val width = findViewById<Slider>(R.id.sliderWidth)
        val ja = findViewById<MaterialSwitch>(R.id.switchJa)
        val stats = findViewById<MaterialSwitch>(R.id.switchStats)
        val lSize = findViewById<TextView>(R.id.labelSize)
        val lAlpha = findViewById<TextView>(R.id.labelAlpha)
        val lWidth = findViewById<TextView>(R.id.labelWidth)
        val height = findViewById<Slider>(R.id.sliderHeight)
        val lHeight = findViewById<TextView>(R.id.labelHeight)
        val keepS = findViewById<Slider>(R.id.sliderKeepSentences)
        val keepC = findViewById<Slider>(R.id.sliderKeepChars)
        val lKeepS = findViewById<TextView>(R.id.labelKeepSentences)
        val lKeepC = findViewById<TextView>(R.id.labelKeepChars)
        val keepHint = findViewById<TextView>(R.id.keepHint)
        val paletteRow = findViewById<LinearLayout>(R.id.paletteRow)
        // the fade says "there is more to the right"; once scrolled to the end there is nothing left to hint at
        val paletteScroll = findViewById<android.widget.HorizontalScrollView>(R.id.paletteScroll)
        val paletteFade = findViewById<View>(R.id.paletteFade)
        fun updatePaletteFade() { paletteFade.visibility = if (paletteScroll.canScrollHorizontally(1)) View.VISIBLE else View.GONE }
        paletteScroll.setOnScrollChangeListener { _, _, _, _, _ -> updatePaletteFade() }
        val dpx = resources.displayMetrics.density
        // one chip per palette (magpie's .chip): a round sample of its background and text colour, then its name;
        // the chosen one gets an ink outline
        fun renderPalettes() {
            paletteRow.removeAllViews()
            for (p in CaptionOverlayView.PALETTES) {
                val sel = p.id == CaptionOverlayView.palette(prefs.palette).id
                paletteRow.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    contentDescription = p.label + if (sel) "（已选）" else ""
                    minimumHeight = (48 * dpx).toInt()
                    setPadding((5 * dpx).toInt(), 0, (12 * dpx).toInt(), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 22 * dpx; setColor(getColor(R.color.card))
                        setStroke(((if (sel) 2 else 1) * dpx).toInt().coerceAtLeast(1), getColor(if (sel) R.color.text else R.color.line))
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = "字"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(p.zh); typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(p.bg or 0xFF000000.toInt()); setStroke((1 * dpx).toInt().coerceAtLeast(1), 0x33888888)
                        }
                    }, LinearLayout.LayoutParams((32 * dpx).toInt(), (32 * dpx).toInt()))
                    addView(TextView(this@MainActivity).apply {
                        text = p.label; textSize = 14f
                        setTextColor(getColor(if (sel) R.color.text else R.color.text2))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
                    setOnClickListener { prefs.palette = p.id; renderPalettes(); preview.setColors(p) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dpx).toInt() })
            }
            paletteScroll.post { updatePaletteFade() } // the row's real width is known only after this layout pass
        }

        var loading = false // programmatic updates must not be written back
        fun load() {
            loading = true
            size.value = prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP).let { Math.round(it).toFloat() }
            alpha.value = (Math.round(prefs.bgAlpha * 20) * 5f).coerceIn(0f, 100f)
            width.value = (Math.round(prefs.widthFrac * 20) * 5f).coerceIn(40f, 100f)
            ja.isChecked = prefs.showJa
            stats.isChecked = prefs.showStats
            // only a real 0 is "auto": a small dragged height still shows as fixed (at least the first step)
            height.value = if (prefs.heightFrac <= 0f) 0f else (Math.round(prefs.heightFrac * 20) * 5f).coerceIn(5f, 40f)
            keepS.value = prefs.keepSentences.coerceIn(1, 6).toFloat()
            keepC.value = (Math.round(prefs.keepChars / 10f) * 10f).coerceIn(0f, 300f)
            loading = false
        }
        fun apply() {
            lSize.text = "文字大小  ${size.value.toInt()}"
            // the slider moves in 5% steps; until the user drags it, the label and preview keep the stored value
            // (the 78% default sits between two steps)
            val shownAlpha = prefs.bgAlpha // dragging saves first, so this is always what the overlay uses
            lAlpha.text = "背景浓淡  ${Math.round(shownAlpha * 100)}%"
            lWidth.text = "字幕宽度  ${width.value.toInt()}%"
            lHeight.text = if (prefs.heightFrac <= 0f) "字幕高度  自动（跟着内容变）" else "字幕高度  屏幕的 ${Math.round(prefs.heightFrac * 100)}%"
            lKeepS.text = "屏幕上至少留  ${keepS.value.toInt()} 句"
            lKeepC.text = "字数超过  ${keepC.value.toInt()} 字后，收起最早的句子"
            keepHint.text = "屏幕上会留着刚说完的几句话，方便回看；句数和字数都超过上面的数字时，最早的一句才会收起。字幕框放不下时，只显示最新的内容。"
            preview.setColors(CaptionOverlayView.palette(prefs.palette))
            preview.applyStyle(size.value, shownAlpha)
            // same caption height as the overlay will get (the stage grows with it)
            preview.setMaxContentHeight((resources.displayMetrics.heightPixels * CaptionService.MAX_CAPTION_FRAC).toInt(), Int.MAX_VALUE)
            preview.setCaptionHeight((resources.displayMetrics.heightPixels * prefs.heightFrac).toInt())
            preview.layoutParams = FrameLayout.LayoutParams((findViewById<View>(R.id.previewStage).width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 0.8f).toInt()).let { (it * width.value / 100f).toInt() }, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            preview.setLines(
                listOf(CaptionOverlayView.Line("这一句已经说完，是正式字幕", if (ja.isChecked) "（这里显示原文）" else null, true),
                    CaptionOverlayView.Line("正在说的这一句…", if (ja.isChecked) "（原文）" else null, false)),
                if (stats.isChecked) "这里显示识别和翻译用了多久" else null,
            )
        }
        loadStyle = { load(); renderPalettes(); apply() }
        load(); renderPalettes(); apply()
        findViewById<View>(R.id.previewStage).post { apply() } // the stage's real width is known after layout
        size.addOnChangeListener { _, v, user -> if (user) { prefs.textSp = v; apply() } }
        alpha.addOnChangeListener { _, v, user -> if (user) { prefs.bgAlpha = v / 100f; apply() } }
        width.addOnChangeListener { _, v, user -> if (user) { prefs.widthFrac = v / 100f; apply() } }
        ja.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showJa = v; apply() } }
        stats.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showStats = v; apply() } }
        height.addOnChangeListener { _, v, user -> if (user) { prefs.heightFrac = v / 100f; apply() } }
        keepS.addOnChangeListener { _, v, user -> if (user) { prefs.keepSentences = v.toInt(); apply() } }
        keepC.addOnChangeListener { _, v, user -> if (user) { prefs.keepChars = v.toInt(); apply() } }
        findViewById<MaterialButton>(R.id.resetStyle).setOnClickListener {
            prefs.setStyle(13f, CaptionOverlayView.DEFAULT_ALPHA); prefs.widthFrac = Prefs.DEFAULT_WIDTH; prefs.showJa = true; prefs.showStats = true
            prefs.keepSentences = CaptionBoard.DEFAULT_SENTENCES; prefs.keepChars = CaptionBoard.DEFAULT_CHARS; prefs.heightFrac = 0f
            prefs.palette = CaptionOverlayView.PALETTES[0].id; renderPalettes()
            prefs.overlayX = -1; prefs.overlayY = -1; prefs.overlayBottom = -1
            load(); apply()
        }
    }

    // ---- start ---------------------------------------------------------------------------------------------

    private fun start() {
        startRefused = false
        // first use: without an LLM API there is nothing to start with, so the one action opens the page that fills
        // it in — the home card already says which part is missing
        if (!prefs.byokReady) {
            selectPage(2)
            Toast.makeText(this, "先填好 LLM API，再回首页点开始字幕", Toast.LENGTH_SHORT).show()
            return
        }
        // a connectivity test still running would probe the same service again alongside the session's own probe
        byokTest?.let { it.cancel(); byokTest = null; renderTranslate() }
        if (!hasAudio()) {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS // optional
            requestPermissions(ask.toTypedArray(), REQ_START)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            // the system page is a detour: coming back with the permission granted continues the start by itself
            startPending = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        renderPermissions()
        render()
        if (requestCode == REQ_START && hasAudio()) start()
        else if (requestCode == REQ_START) status(R.color.err, "需要允许「听手机里播放的声音」", "只用来听 App 播放出来的声音，不会用麦克风。请再点一次开始，并选择「允许」。")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            // Seen on Android 15 with "a single app": the system refused the foreground service here
            // (mAllowStartForeground false). Show it instead of crashing; the exact trigger is not pinned down.
            try {
                CaptionService.start(this, resultCode, data)
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                startRefused = true; render()
                return
            }
            moveTaskToBack(true)
        }
    }

    private companion object {
        /** set by an activity a configuration change ends during a manual update check, read by its replacement */
        var manualCheckStopped = false
        const val REQ_START = 1      // runtime permission asked on the way to starting subtitles
        const val REQ_PROJECTION = 2
        val STYLE_KEYS = setOf("textSp", "bgAlpha", "widthFrac", "heightFrac", "showJa", "showStats", "keepSentences", "keepChars", "palette")
        const val REQ_PERM_ONLY = 3  // "去开启" in the permission list: grant only, never start
        const val LOGS_BEFORE_SCROLL = 5
        /** where the project lives (set by the author; the repository may not be public yet) */
        // DIY: 指向自己的 fork——上游 release 的签名和本包不一致，应用内更新装不上
        const val REPO_URL = "https://github.com/J1nxi-youmi/simul-subtitle-diy"
        /** 本次会话 builds at most this many sentences into views; 导出 always has the whole session */
        const val SESSION_MAX_ROWS = 300
    }
}
