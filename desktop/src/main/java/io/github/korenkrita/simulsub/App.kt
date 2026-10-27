package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

private val prefs get() = Prefs.prefs
private const val REPO = "https://github.com/J1nxi-youmi/simul-subtitle-diy"

private fun open(url: String) = runCatching { Desktop.getDesktop().browse(URI(url)) }
private fun reveal(f: File) = runCatching {
    ProcessBuilder("explorer.exe", "/select,", f.path).start()
}
private fun sizeText(b: Long) = if (b >= 1e6) "%.1f MB".format(b / 1e6) else "%.0f KB".format(b / 1e3)

/** Starts captions from the home screen, the menu bar or the toolbar; null when it cannot, with the reason. */
private fun startCaptions(): String? {
    if (!prefs.byokReady) return "LLM API 还没填完整：请在设置「翻译」里填好服务地址、模型 ID 和 API Key 并保存。"
    if (!sysaudioFile.canExecute()) return "找不到系统声音采集程序（${sysaudioFile.path}）"
    // a 测试连接 still running would probe the same service the session probes on first use (billed twice)
    byokTest.value?.cancel(); byokTest.value = null
    CaptionService.start(sysaudioFile)
    return null
}

fun app() = application {
    var mainOpen by remember { mutableStateOf(true) }
    var startError by remember { mutableStateOf<String?>(null) }
    val running by CaptionService.runningState
    val dark = when (prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    // Windows: the taskbar's own theme (it can be dark while apps are light), checked again every few seconds
    var trayDark by remember { mutableStateOf(taskbarDark()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); trayDark = taskbarDark() } }
    // Ctrl+Alt+T toggles 鼠标穿透: the switch itself is in the main window, which may be closed
    LaunchedEffect(Unit) {
        OverlayHotkey.start {
            java.awt.EventQueue.invokeLater { prefs.overlayClickThrough = !prefs.overlayClickThrough }
        }
    }

    // The tray is the fallback: the main window can be closed, and with 穿透 on the overlay cannot be clicked at all,
    // so every way back to it has to be reachable from here.
    Tray(icon = TrayIcon(running, trayDark), tooltip = "同传字幕", menu = {
        Item("显示主窗口", onClick = { mainOpen = true })
        if (running) Item("停止字幕", onClick = { CaptionService.stop() })
        else Item("开始字幕", onClick = { startError = startCaptions(); if (startError != null) mainOpen = true })
        Item(if (prefs.overlayShown) "隐藏悬浮字幕" else "显示悬浮字幕") {
            prefs.overlayShown = !prefs.overlayShown
        }
        if (prefs.overlayClickThrough) Item("恢复浮窗操作（解除穿透）") { prefs.overlayClickThrough = false }
        Separator()
        Item("退出", onClick = { CaptionService.stop(); exitApplication() })
    })

    // the caption window lives with the session, not with the main window
    CaptionService.current?.takeIf { running }?.let { s ->
        Themed(true) { OverlayWindow(s.overlay, prefs, onStop = { CaptionService.stop() }) }
    }

    if (mainOpen) {
        val state = rememberWindowState(size = DpSize(970.dp, 660.dp))
        // closing the window keeps the app (and its captions) in the menu bar
        Window(onCloseRequest = { mainOpen = false }, state = state, title = "同声传译（diy版）", icon = painterResource("icon.png")) {
            // the title bar is the system's: follow the page colour, not just the system's own setting
            Themed(dark) {
                Box(Modifier.fillMaxSize().background(C.bg)) { MainScreen(startError) { startError = it } }
            }
        }
    }
    AutoUpdateCheck()
}

/** True when the Windows taskbar is dark (the default), from the registry. */
private fun taskbarDark(): Boolean = runCatching {
    com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme") == 0
}.getOrDefault(true)

/**
 * The notification area icon: a caption card, filled while captions run. Drawn, so it follows no file.
 * White on a dark Windows taskbar, black on a light one.
 */
private class TrayIcon(val on: Boolean, val dark: Boolean) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val w = size.width; val h = size.height
        val c = if (dark) Color.White else Color.Black
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.14f)
        val tl = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.2f)
        val sz = Size(w * 0.84f, h * 0.6f)
        if (on) drawRoundRect(c, tl, sz, r)
        else drawRoundRect(c, tl, sz, r, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.07f))
        // the lines on a filled card are cut out (transparent), so a template image keeps them
        val blend = if (on) androidx.compose.ui.graphics.BlendMode.Clear else androidx.compose.ui.graphics.BlendMode.SrcOver
        drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.42f), Size(w * 0.52f, h * 0.07f), blendMode = blend)
        drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.56f), Size(w * 0.36f, h * 0.07f), blendMode = blend)
    }
    override fun equals(other: Any?) = other is TrayIcon && other.on == on && other.dark == dark
    override fun hashCode() = on.hashCode() * 31 + dark.hashCode()
}

/** the pages the sidebar offers: the four settings groups are pages of their own, like the reference app */
private val PAGE_TITLES = listOf("同传字幕", "字幕", "翻译", "页面", "记录", "关于")

@Composable
private fun MainScreen(startError: String?, setStartError: (String?) -> Unit) {
    // 0 首页, 1 字幕, 2 翻译, 3 页面, 4 记录, 5 关于 — the sidebar replaces the old settings tabs
    var page by remember { mutableIntStateOf(0) }
    val openTranslate = { page = 2 }
    Row(Modifier.fillMaxSize()) {
        Sidebar(page) { page = it }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 18.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(PAGE_TITLES[page], fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (page == 0) {
                            Text("把视频、直播里的声音，实时变成中文字幕", color = C.text2, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    // the overlay's own switches: they belong next to the page that starts it, not in a settings tab
                    if (page == 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OverlayToggle("悬浮字幕", prefs.overlayShown) { prefs.overlayShown = !prefs.overlayShown }
                            OverlayToggle("置顶", prefs.overlayOnTop) { prefs.overlayOnTop = !prefs.overlayOnTop }
                            OverlayToggle("鼠标穿透", prefs.overlayClickThrough) { prefs.overlayClickThrough = !prefs.overlayClickThrough }
                        }
                    }
                }
            }
            if (page == 0) {
                Column(Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp)) {
                    Home(Modifier.weight(1f))
                    HomeFooter(startError, setStartError, openTranslate)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, bottom = 22.dp)) {
                    when (page) {
                        1 -> CaptionTab()
                        2 -> TranslateTab()
                        3 -> PageTab()
                        4 -> LogsTab()
                        else -> AboutSection()
                    }
                }
            }
        }
    }
}

/** a dot + label switch, like the reference app's 悬浮字幕 / 置顶 line */
@Composable
private fun OverlayToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable { onClick() }.padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(if (on) C.ok else C.faint)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = if (on) C.text else C.muted)
    }
}

@Composable
private fun Sidebar(page: Int, onPick: (Int) -> Unit) {
    Column(Modifier.width(200.dp).fillMaxHeight().background(C.pill)) {
        Spacer(Modifier.height(10.dp))
        for (i in 0..4) SidebarItem(PAGE_TITLES[i], page == i) { onPick(i) }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) { Divider() }
        Spacer(Modifier.height(12.dp))
        SidebarItem(PAGE_TITLES[5], page == 5) { onPick(5) }
    }
}

@Composable
private fun SidebarItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
        .then(if (selected) Modifier.background(C.card, RoundedCornerShape(10.dp)) else Modifier)
        .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) C.text else C.text2)
    }
}

// ---- home --------------------------------------------------------------------------------------------------

@Composable
private fun Home(modifier: Modifier = Modifier) {
    // the session panel is the whole page: the languages moved to 翻译, and no hint stands between them
    SessionPanel(modifier)
}

/** 本次会话: everything said since the session started, with a filter and a way to keep it as text. */
@Composable
private fun SessionPanel(modifier: Modifier = Modifier) {
    val running by CaptionService.runningState
    val session = CaptionService.current ?: CaptionService.lastSession
    val history = session?.overlay?.history?.value.orEmpty()
    // shown even with nothing in it: a panel that appears only once captions arrive looks like a missing feature
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) history else history.filter {
        it.zh.contains(query, ignoreCase = true) || it.ja.orEmpty().contains(query, ignoreCase = true)
    }
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本次会话", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("${history.size} 条", color = C.muted, fontSize = 12.sp)
            Grow()
            Btn("导出…", enabled = history.isNotEmpty()) { exportCaptions(history) }
            Spacer(Modifier.width(8.dp))
            Btn("清空", enabled = history.isNotEmpty()) { session?.overlay?.history?.value = emptyList() }
        }
        Spacer(Modifier.height(8.dp))
        Field(query, { query = it }, "搜索这次会话的字幕")
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (history.isEmpty()) "开始字幕后，这里会一条条列出这次说过的句子。" else "没有匹配的句子。",
                    color = C.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        } else {
            val listScroll = rememberScrollState()
            LaunchedEffect(shown.size) { listScroll.scrollTo(listScroll.maxValue) }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(listScroll)) {
                // newest last, like a chat: the list is read top-down while new lines appear at the bottom
                for (l in shown) {
                    l.ja?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = C.muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Text(l.zh, color = C.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}

/** Saves the session as plain text: the source line under each translation, one sentence per block. */
private fun exportCaptions(lines: List<CaptionLine>) {
    val dlg = java.awt.FileDialog(null as java.awt.Frame?, "导出字幕", java.awt.FileDialog.SAVE)
    dlg.file = "字幕 " + java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(java.util.Date()) + ".txt"
    dlg.isVisible = true
    val name = dlg.file ?: return
    val dir = dlg.directory ?: return
    val text = lines.joinToString("\n\n") { l -> l.ja?.takeIf { it.isNotBlank() }?.let { "$it\n${l.zh}" } ?: l.zh }
    runCatching { java.io.File(dir, name).writeText(text, Charsets.UTF_8) }
}

/** The one action of 首页 plus what it will use: bottom-left, next to each other, as in the reference app. */
@Composable
private fun HomeFooter(startError: String?, setStartError: (String?) -> Unit, openTranslate: () -> Unit) {
    val running by CaptionService.runningState
    val ended by CaptionService.endedState
    val ready = prefs.byokReady
    // an error is one line here: the home page has no room for a status card above the action
    startError?.let {
        Text(it, color = C.err, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            running && ended -> Btn("关掉字幕") { CaptionService.stop() }
            running -> StartButton(C.err, "停止字幕", Color.White) { CaptionService.stop() }
            // not configured: the button is the way to the page that fixes it
            !ready -> StartButton(C.brand, "开始字幕", C.onBrand, openTranslate)
            else -> StartButton(C.brand, "开始字幕", C.onBrand) { setStartError(startCaptions()) }
        }
        Spacer(Modifier.width(14.dp))
        if (modelsReady()) StatusChip("引擎 已就绪", C.ok) else StatusChip("引擎 未安装", C.warn)
        Spacer(Modifier.width(8.dp))
        StatusChip(if (ready) "翻译 ${prefs.byokModel}" else "翻译 未配置", if (ready) C.ok else C.warn)
        Spacer(Modifier.width(8.dp))
        StatusChip(if (prefs.finalOnly) "说完再出" else "边听边出", C.muted)
    }
}

@Composable
private fun StartButton(fill: Color, label: String, textColor: Color, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).background(fill, RoundedCornerShape(10.dp)).clickable { onClick() }
        .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** a pill under the start button: engine / translation model / sentence mode */
@Composable
private fun StatusChip(label: String, tint: Color) {
    Row(Modifier.background(C.pill, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(tint)
        Spacer(Modifier.width(7.dp))
        Text(label, color = C.text2, fontSize = 12.sp)
    }
}

// ---- 字幕 --------------------------------------------------------------------------------------------------

@Composable
private fun CaptionTab() {
    val sample = remember { OverlayState() }
    // the display the overlay is measured against; the width is stored in pixels, so this is only a range
    val screenW = remember { java.awt.Toolkit.getDefaultToolkit().screenSize.width }
    val cardPx = prefs.overlayWidth(screenW)
    sample.lines.value = listOf(CaptionLine("这一句已经说完，是正式字幕", if (prefs.showJa) "（这里显示原文）" else null, true),
        CaptionLine("正在说的这一句…", if (prefs.showJa) "（原文）" else null, false))
    sample.diag.value = if (prefs.showStats) "这里显示识别和翻译用了多久" else null
    Group("外观") {
        Box(Modifier.fillMaxWidth().background(Color(0xFF3A3A40), RoundedCornerShape(10.dp)).padding(vertical = 16.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            CaptionCard(sample, prefs, modifier = Modifier.fillMaxWidth((cardPx.toFloat() / screenW).coerceIn(Prefs.MIN_WIDTH_PX.toFloat() / screenW, 1f)))
        }
        Label("字幕配色")
        // wraps onto more lines: a sideways scroll needs a trackpad (a mouse wheel scrolls the page)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in PALETTES) {
                val sel = p.id == palette(prefs.palette).id
                Row(Modifier.height(40.dp).background(C.card, RoundedCornerShape(20.dp))
                    .border(if (sel) 2.dp else 1.dp, if (sel) C.text else C.line, RoundedCornerShape(20.dp))
                    .clickable { prefs.palette = p.id }.padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(Color(p.bg or 0xFF000000), CircleShape).border(1.dp, Color(0x33888888), CircleShape),
                        contentAlignment = Alignment.Center) { Text("字", color = Color(p.zh), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    Text(p.label, fontSize = 14.sp, color = if (sel) C.text else C.text2)
                }
            }
        }
        Label("文字大小  ${Math.round(prefs.textSp.coerceIn(MIN_SP, MAX_SP))}")
        Slider2(Math.round(prefs.textSp.coerceIn(MIN_SP, MAX_SP)).toFloat(), MIN_SP..MAX_SP, (MAX_SP - MIN_SP).toInt() - 1) { prefs.textSp = Math.round(it).toFloat() }
        Label("背景浓淡  ${Math.round(prefs.bgAlpha * 100)}%")
        Slider2(prefs.bgAlpha * 100, 0f..100f, 19) { prefs.bgAlpha = Math.round(it / 5) * 5 / 100f }
    }
    Group("显示") {
        SwitchRow("同时显示原文", null, prefs.showJa) { prefs.showJa = it }
        Divider()
        SwitchRow("显示识别和翻译用时", null, prefs.showStats) { prefs.showStats = it }
    }
    Group("字幕框大小和保留几句") {
        Label("字幕宽度  ${cardPx} px")
        // pixels, not a part of the screen: a danmaku-like strip means the same width on every monitor
        Slider2(cardPx.toFloat(), Prefs.MIN_WIDTH_PX.toFloat()..screenW.toFloat(), ((screenW - Prefs.MIN_WIDTH_PX) / 10) - 1) {
            prefs.widthPx = Math.round(it / 10f) * 10
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("窄条 302") { prefs.widthPx = 302 }
            Btn("中 640") { prefs.widthPx = 640 }
            Btn("宽屏") { prefs.widthPx = Math.round(screenW * 0.9f) }
        }
        Label(if (prefs.heightFrac <= 0f) "字幕高度  自动（跟着内容变）" else "字幕高度  屏幕的 ${Math.round(prefs.heightFrac * 100)}%")
        Slider2(if (prefs.heightFrac <= 0f) 0f else (Math.round(prefs.heightFrac * 20) * 5f).coerceIn(5f, 40f), 0f..40f, 7) { prefs.heightFrac = Math.round(it / 5) * 5 / 100f }
        Label("屏幕上至少留  ${prefs.keepSentences.coerceIn(1, 6)} 句")
        Slider2(prefs.keepSentences.coerceIn(1, 6).toFloat(), 1f..6f, 4) { prefs.keepSentences = Math.round(it) }
        Label("字数超过  ${(Math.round(prefs.keepChars / 10f) * 10).coerceIn(0, 300)} 字后，收起最早的句子")
        Slider2((Math.round(prefs.keepChars / 10f) * 10f).coerceIn(0f, 300f), 0f..300f, 29) { prefs.keepChars = Math.round(it / 10) * 10 }
        Hint("屏幕上会留着刚说完的几句话，方便回看；句数和字数都超过上面的数字时，最早的一句才会收起。字幕框放不下时，只显示最新的内容。字幕框也可以直接拖动：点一下字幕，拖四个角上的小箭头。")
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Grow()
        TextBtn("恢复默认") { prefs.resetStyle(); prefs.setOverlay(null, null, null, Prefs.DEFAULT_WIDTH, 0f) }
    }
}

// ---- 翻译 --------------------------------------------------------------------------------------------------

/** The scene being edited in 设置 › 翻译 › 内容 (the editor's text), null while closed. [old] null = 新建. */
private class SceneDraft(val old: Scene?) {
    var label by mutableStateOf(old?.label ?: "")
    var background by mutableStateOf(old?.background ?: "")
    /** the term rows as typed (原文, 译文); a new scene starts with one empty row */
    var terms by mutableStateOf(old?.terms?.ifEmpty { null } ?: listOf("" to ""))
    /** the language the 译文 are in (a new scene: the captions' language at the time) */
    var termsLang by mutableStateOf(old?.termsLang ?: prefs.targetLang)
    var count by mutableStateOf("20")
    var status by mutableStateOf<String?>(null)
    var generating by mutableStateOf(false)
    var confirm by mutableStateOf(false)
}
private val sceneDraft = mutableStateOf<SceneDraft?>(null)

/**
 * 内容类型: pick a scene, 编辑 it or 新建 one. The editor: name, background, terms one per line; 生成术语 asks the LLM
 * API for up to the typed number of terms and only appends them (lines already there, saved or typed, stay as they
 * are); 保存 checks the text; 删除 a user's scene, 恢复默认 an edited built-in (each confirmed once).
 */
@Composable
private fun Scenes() {
    val scenes = prefs.scenes
    val scene = prefs.scene
    val d = sceneDraft.value
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dropdown(scenes.map { it.label }, scenes.indexOfFirst { it.id == scene.id }.coerceAtLeast(0), enabled = d == null,
            modifier = Modifier.weight(1f)) { prefs.scene = scenes[it] }
        Spacer(Modifier.width(8.dp))
        Btn("编辑", enabled = d == null) { sceneDraft.value = SceneDraft(scene) }
        Spacer(Modifier.width(8.dp))
        Btn("新建", enabled = d == null) { sceneDraft.value = SceneDraft(null) }
    }
    if (d == null) {
        Hint(scene.summary(prefs.targetLang))
        return
    }
    Spacer(Modifier.height(10.dp))
    Text(if (d.old == null) "新建内容类型" else "编辑「${d.old.label}」", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Label("名字")
    Field(d.label, { d.label = it.take(Scene.MAX_LABEL) }, "例如 游戏实况、某主播的直播")
    Label("背景说明")
    Area(d.background, { d.background = it }, "这是什么内容、谁在说话、在聊什么，翻译会参考", 2)
    Label("术语")
    // the language the 译文 are in: the terms are used only when the captions are in it
    val captions = prefs.targetLang
    val langs = (Target.PRESETS + d.termsLang + captions).distinct()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("译文是", color = C.text, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
        Dropdown(langs.map { it.zh }, langs.indexOf(d.termsLang), modifier = Modifier.weight(1f)) { d.termsLang = langs[it] }
    }
    Hint(if (d.termsLang == captions) "现在字幕翻成${captions.zh}，这些术语会用上。"
        else "现在字幕翻成${captions.zh}，这些${d.termsLang.zh}术语不会用上；字幕换成${d.termsLang.zh}才用。")
    Hint("左边写视频里说的词，右边写想要的译法，例如 ボス 和 头目。")
    // one row of two boxes each; the arrow between them is ours
    d.terms.forEachIndexed { i, (src, dst) ->
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(src, { v -> d.terms = d.terms.toMutableList().also { it[i] = v.take(Scene.MAX_TERM) to it[i].second } }, "原文") }
            Text(Scene.TERM_SEP, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Box(Modifier.weight(1f)) { Field(dst, { v -> d.terms = d.terms.toMutableList().also { it[i] = it[i].first to v.take(Scene.MAX_TERM) } }, "译文") }
            TextBtn("×") { d.terms = d.terms.toMutableList().also { it.removeAt(i) } }
        }
    }
    TextBtn("+ 加一条") { if (d.terms.size < Scene.MAX_TERMS) d.terms = d.terms + ("" to "") }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Btn(if (d.generating) "生成中…" else "生成术语", enabled = !d.generating) {
            val service = prefs.byokService()
            val chat = service?.url
            val n = d.count.trim().toIntOrNull()
            d.status = when {
                service == null || service.model.isEmpty() -> "先在上面「LLM API」里填好服务并保存。"
                d.label.isBlank() && d.background.isBlank() -> "先写名字或背景说明，LLM 才知道要哪类术语。"
                n == null || n < 1 || n > MAX_GENERATE -> "生成条数填 1 到 $MAX_GENERATE。"
                d.terms.count { it.first.isNotBlank() || it.second.isNotBlank() } >= Scene.MAX_TERMS -> "术语已经有 ${Scene.MAX_TERMS} 条，满了。"
                else -> null
            }
            if (d.status != null) return@Btn
            d.generating = true; d.status = "正在生成…（最多一分钟）"
            val model = prefs.byokModel; val stored = prefs.byokVariant(chat!!, model)
            val label = d.label.trim(); val bg = d.background; val src = prefs.sourceLang; val tgt = d.termsLang
            val have = Scene.sources(d.terms)
            val room = Scene.MAX_TERMS - d.terms.count { it.first.isNotBlank() || it.second.isNotBlank() }
            Thread {
                val (got, err) = generateTerms(service!!, stored, { prefs.setByokVariant(chat, model, it) }, label, bg, have, minOf(n!!, room), src, tgt)
                java.awt.EventQueue.invokeLater {
                    d.generating = false
                    if (sceneDraft.value !== d) return@invokeLater // closed meanwhile
                    // appended to what is in the box now (the user may have typed meanwhile), skipping 原文 now there
                    // a lone empty row (the one a new scene starts with) gives way to the generated ones
                    val now = d.terms.takeUnless { it.size == 1 && it[0].first.isBlank() && it[0].second.isBlank() }.orEmpty()
                    val (text, added) = appendTerms(now, got.orEmpty())
                    d.status = when {
                        err != null -> "⚠ 没能生成：$err"
                        added == 0 -> "没有生成新的术语（可能都已经有了，或者满了），可以把背景说明写详细些再试。"
                        else -> { d.terms = text; "加了 $added 条，看一下有没有不对的，再点「保存」。" }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        Text("最多", color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp, end = 6.dp))
        Box(Modifier.width(64.dp)) { Field(d.count, { d.count = it.filter(Char::isDigit).take(3) }, "20") }
        Text("条", color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
    }
    d.status?.let { Hint(it, color = if (it.startsWith("⚠")) C.err else C.text2) }
    Hint("「生成术语」用你填的 LLM API，按名字和背景说明想出这类内容的常见词，只往下面加，不改也不删已有的术语。")
    Spacer(Modifier.height(8.dp))
    val old = d.old
    if (d.confirm && old != null) {
        Note(if (old.builtin) "把「${old.label}」恢复成自带的说明和术语？你改过的内容会丢掉。" else "删除「${old.label}」？")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Grow()
            TextBtn("取消") { d.confirm = false }
            Btn(if (old.builtin) "恢复默认" else "删除", danger = true) {
                val shipped = Scene.DEFAULTS.firstOrNull { it.id == old.id }
                val inUse = prefs.scene.id == old.id
                prefs.scenes = if (shipped != null) prefs.scenes.map { if (it.id == old.id) shipped else it }
                    else prefs.scenes.filter { it.id != old.id }
                if (shipped == null && inUse) prefs.scene = prefs.scenes.first()
                sceneDraft.value = null
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            old != null && !old.builtin -> TextBtn("删除", danger = true) { d.confirm = true }
            old != null && !old.shipped -> TextBtn("恢复默认") { d.confirm = true }
        }
        Grow()
        TextBtn("取消") { sceneDraft.value = null }
        Btn("保存", primary = true) {
            val (s, err) = Scene.edit(old?.id ?: "u${System.currentTimeMillis()}", d.label, d.background, d.terms, d.termsLang)
            if (s == null) { d.status = "⚠ $err"; return@Btn }
            val list = prefs.scenes
            prefs.scenes = if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
            prefs.scene = s
            sceneDraft.value = null
        }
    }
}

/** the running BYOK connection test, so a second click or a new screen does not show a stale result */
private val byokTest = mutableStateOf<ByokTranslator?>(null)
private val byokResult = mutableStateOf<String?>(null)

@Composable
private fun TranslateTab() {
    val running by CaptionService.runningState
    if (running) Note("字幕正在运行：内容类型、结合上文、去掉重复的字、LLM API（切换配置或点保存后）和 Jev 在线模型服务、key 改了从下一句开始生效；智能断句和检查译文从下次开始字幕时生效；显示方式马上生效。")
    /** the 字幕语言 being typed for 自己填… (null: that editor is closed) */
    var typing by remember { mutableStateOf<String?>(null) }
    // the languages moved here from the home page: 识别语种 → 字幕语言, as one row
    Group("语言") {
        val asrLangs = listOf("ja" to "日语", "auto" to "自动（中日英韩粤）", "zh" to "中文", "ko" to "韩语", "yue" to "粤语")
        val presets = Target.PRESETS
        val current = prefs.targetLang
        val targets = if (current in presets) presets else presets + current
        // the captions share the two boxes' widths, so the arrow sits exactly between the dropdowns
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("识别语种", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("→", color = Color.Transparent, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp))
            Text("字幕语言", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Dropdown(asrLangs.map { it.second }, asrLangs.indexOfFirst { it.first == prefs.asrSvLang }.coerceAtLeast(0), modifier = Modifier.weight(1f)) {
                val code = asrLangs[it].first
                prefs.asrSvLangSetting = code
                prefs.sourceLang = Lang.of(code) // 识别成什么语种，翻译的源语言就跟着它
            }
            Text("→", color = C.muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp))
            Dropdown(targets.map { it.zh } + "自己填…", targets.indexOf(current), modifier = Modifier.weight(1f)) {
                if (it == targets.size) typing = current.takeIf { c -> c.lang == null }?.name ?: ""
                else { typing = null; prefs.targetLang = targets[it] }
            }
        }
        // 自己填…: a typed language name, saved with 确定; 取消 or a blank name keeps the language in use
        typing?.let { t ->
            Spacer(Modifier.height(10.dp))
            Field(t, { v -> typing = v.take(Target.MAX_NAME) }, "例如 繁体中文、粤语、文言文")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn("取消") { typing = null }
                Btn("确定", primary = true, enabled = Target.custom(t) != null) {
                    Target.custom(t)?.let { prefs.targetLang = it }; typing = null
                }
            }
        }
        Hint("「自动」容易把日语听成中文或英文，纯日语场次选「日语」，改了下次开始字幕时生效。")
        Hint("字幕语言：识别出的文字翻成它，运行中改了从下一句开始生效。")
    }
    Group("LLM API") { Byok(running); ByokAdvanced() }
    Group("内容") {
        Label("内容类型")
        Scenes()
        SwitchRow("结合上文来翻译", "翻译每句话时，把前面刚说过的几句一起交给翻译参考，帮助它理解人称、指代和省略的内容。参考的句子越多，翻译要处理的文字越多，可能更慢。",
            prefs.contextOn) { prefs.contextOn = it }
        if (prefs.contextOn) {
            Label("参考前面 ${prefs.contextSentences} 句")
            Slider2(prefs.contextSentences.toFloat(), 1f..Pipeline.MAX_PREVIOUS_SENTENCES.toFloat(), Pipeline.MAX_PREVIOUS_SENTENCES - 2) {
                prefs.contextSentences = Math.round(it)
            }
        }
    }
    Group("字幕怎么出现") {
        Seg(listOf(false to "边听边出", true to "说完再出"), prefs.finalOnly) { prefs.finalOnly = it }
        Hint("「边听边出」：一边说一边显示，灰色的字还会修改，说完一句后变成正式字幕。「说完再出」：每句话说完才显示，画面更干净，但会晚一点。字幕上的工具栏也能随时切换。")
        SwitchRow("去掉重复的字", "一句话分段识别时，接口处偶尔会把同一段话识别两遍。打开后自动去掉多出来的那一遍；如果说话人真的重复说了，也可能被去掉。",
            prefs.dedupeJoin) { prefs.dedupeJoin = it }
    }
    Group("断句") {
        val pauses = listOf(200, 300, 350, 500, 800)
        Label("停顿切段")
        Dropdown(pauses.map { "%.2f 秒".format(it / 1000f) }, pauses.indexOf(prefs.endSilenceMs).let { if (it < 0) 2 else it }) {
            prefs.endSilenceSetting = pauses[it]
        }
        val maxes = listOf(3_000, 4_000, 6_000, 8_000, 12_000)
        Label("单句最长")
        Dropdown(maxes.map { "%.1f 秒".format(it / 1000f) }, maxes.indexOf(prefs.maxUtteranceMs).let { if (it < 0) 2 else it }) {
            prefs.maxUtteranceSetting = maxes[it]
        }
        Hint("调小出字更快但句子更碎（太碎识别会变差）；调大句子更完整但更晚出现。下次开始字幕时生效。")
    }
    Group("译文检查（可选，需要联网）") {
        Hint("建议只在用 qwen-mt 这类单句翻译模型时才打开：它看不到上下文，容易把否定或转折翻反。用 qwen-flash 这类通用模型时通常不必开——每句会多一次请求，延迟约 +0.4 秒。")
        SwitchRow("检查译文", "每句译文出来后由模型再看一遍：有没有把意思译反、漏了否定、把比喻按字面直译。发现问题就带着提示重新翻译这一句。", prefs.jevCheck) { prefs.jevCheck = it }
        if (prefs.jevCheck) {
            var model by remember { mutableStateOf(prefs.judgeModelSetting) }
            var url by remember { mutableStateOf(prefs.judgeUrlSetting) }
            var key by remember { mutableStateOf("") }
            var err by remember { mutableStateOf<String?>(null) }
            Label("校验模型")
            Field(model, { model = it; err = null }, "例如 qwen-flash")
            Label("校验地址（留空则用翻译那套）")
            Field(url, { url = it; err = null }, "例如 https://dashscope.aliyuncs.com/compatible-mode/v1")
            Label(if (prefs.hasJudgeKey) "校验 API Key：已保存（留空则用翻译的 key）" else "校验 API Key（留空则用翻译的 key）")
            Field(key, { key = it; err = null }, if (prefs.hasJudgeKey) "输入新的 key 来替换" else "在这里粘贴 key", password = true)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                err?.let { Text(it, color = C.err, fontSize = 13.sp) }
                Grow()
                TextBtn("清除 key", enabled = prefs.hasJudgeKey) { if (!prefs.setJudgeKey(null)) err = "没能从钥匙串删掉 key，请再试一次" }
                Btn("保存", primary = true) {
                    val k = key.trim()
                    when {
                        k.isNotEmpty() && !Jev.validKey(k) -> err = "key 里有空格、换行或特殊字符，请重新复制"
                        k.isNotEmpty() && !prefs.setJudgeKey(k) -> err = "key 没能存进钥匙串，请再试一次"
                        else -> { prefs.judgeModelSetting = model; prefs.judgeUrlSetting = url; key = "" }
                    }
                }
            }
            Hint("只发送识别出的文字和译文，不发送声音。下次开始字幕时生效。")
        }
    }
}

/**
 * What the LLM API boxes hold but 保存 has not saved yet: address, model, a typed key, "clear the saved key", and the
 * 高级设置 (null: unchanged). All of it is saved by one 保存, so a running session never pairs one service's headers or
 * key with another's address. Outside the composable so switching tabs keeps it.
 */
private class ByokDraft {
    var url by mutableStateOf(prefs.byokUrl)
    var model by mutableStateOf(prefs.byokModel)
    var key by mutableStateOf("")
    var clearKey by mutableStateOf(false)
    var userAgent by mutableStateOf<String?>(null)
    var headers by mutableStateOf<List<Pair<String, String>>?>(null)
}
private val byokDraft = mutableStateOf(ByokDraft())

/** The 高级设置 being edited (null: closed): the User-Agent and the extra headers, as typed. */
private class ByokAdvancedDraft(d: ByokDraft) {
    var userAgent by mutableStateOf(d.userAgent ?: prefs.byokUserAgent)
    var headers by mutableStateOf((d.headers ?: prefs.byokHeaders).ifEmpty { listOf("" to "") })
    var status by mutableStateOf<String?>(null)
}
private val byokAdvanced = mutableStateOf<ByokAdvancedDraft?>(null)

/**
 * 高级设置 of the LLM API: the User-Agent (empty = ours) and extra request headers, one row of two boxes each. 好
 * checks them and puts them in the draft; the LLM API's 保存 saves them with the rest of the service (the headers kept
 * like the key), and a running session takes them from the next sentence.
 */
@Composable
private fun ByokAdvanced() {
    val d = byokAdvanced.value ?: return
    Spacer(Modifier.height(10.dp))
    Text("高级设置", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Label("User-Agent")
    Field(d.userAgent, { d.userAgent = it }, ByokTranslator.defaultUserAgent)
    Hint("空着就用 ${ByokTranslator.defaultUserAgent}。有的服务会拦下不认识的 User-Agent，例如 B 站 Index 的免费接口要求填 Index-Translate-Client/1.0。")
    Label("自定义请求头")
    Hint("左边写名字，右边写值，每个请求都会带上。值和 key 一样保存在加密文件里，使用记录里只记名字。")
    d.headers.forEachIndexed { i, (k, v) ->
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(k, { n -> d.headers = d.headers.toMutableList().also { it[i] = n to it[i].second } }, "名字") }
            Text(":", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Box(Modifier.weight(1.4f)) { Field(v, { n -> d.headers = d.headers.toMutableList().also { it[i] = it[i].first to n } }, "值") }
            TextBtn("×") { d.headers = d.headers.toMutableList().also { it.removeAt(i) } }
        }
    }
    TextBtn("+ 加一个") { if (d.headers.size < ByokService.MAX_HEADERS) d.headers = d.headers + ("" to "") }
    d.status?.let { Hint(it, C.err) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Grow()
        TextBtn("取消") { byokAdvanced.value = null }
        Btn("好", primary = true) {
            val agent = d.userAgent.trim()
            val headers = d.headers.map { it.first.trim() to it.second.trim() }.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
            val err = ByokService.badAdvanced(agent, headers)
            if (err != null) { d.status = "⚠ $err"; return@Btn }
            byokDraft.value.userAgent = agent; byokDraft.value.headers = headers
            byokAdvanced.value = null
            byokResult.value = "高级设置改好了，点「保存」才会生效。"
        }
    }
}

@Composable
private fun Byok(running: Boolean) {
    val profiles = prefs.byokProfiles
    val active = prefs.byokProfile
    var naming by remember { mutableStateOf<Boolean?>(null) }
    var nameText by remember { mutableStateOf("") }
    var nameErr by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    val busy = byokTest.value != null || naming != null || confirmDel
    Label("配置")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dropdown(profiles.map { it.name }, profiles.indexOfFirst { it.id == active.id }.coerceAtLeast(0), enabled = !busy,
            modifier = Modifier.weight(1f)) {
            if (profiles[it].id != active.id) {
                prefs.useByokProfile(profiles[it])
                byokDraft.value = ByokDraft(); byokAdvanced.value = null
                byokResult.value = "已切换到「${profiles[it].name}」，字幕从下一句开始用这个配置。".takeIf { running && prefs.byokReady }
            }
        }
        Spacer(Modifier.width(8.dp))
        Btn("重命名", enabled = !busy) { byokAdvanced.value = null; naming = true; nameText = active.name; nameErr = null }
        Spacer(Modifier.width(8.dp))
        Btn("新建", enabled = !busy && profiles.size < LlmProfile.MAX_PROFILES) { byokAdvanced.value = null; naming = false; nameText = ""; nameErr = null }
        if (profiles.size > 1) {
            Spacer(Modifier.width(8.dp))
            Btn("删除", danger = true, enabled = !busy) { byokAdvanced.value = null; confirmDel = true }
        }
    }
    val testing = byokTest.value != null
    naming?.let { rename ->
        Label(if (rename) "重命名配置" else "新建配置")
        Field(nameText, { nameText = it.take(LlmProfile.MAX_NAME); nameErr = null }, "例如 千问、DeepSeek", enabled = !testing)
        nameErr?.let { Hint(it, C.err) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Grow()
            TextBtn("取消", enabled = !testing) { naming = null }
            Btn("确定", primary = true, enabled = !testing) {
                val (ok, e) = LlmProfile.name(nameText, profiles, self = active.id.takeIf { rename })
                if (ok == null) { nameErr = "⚠ $e"; return@Btn }
                if (rename) prefs.renameByokProfile(active, ok) else {
                    prefs.addByokProfile(ok)
                    byokDraft.value = ByokDraft(); byokAdvanced.value = null; byokResult.value = null
                }
                naming = null
            }
        }
    }
    if (confirmDel) {
        Spacer(Modifier.height(8.dp))
        Note("删除「${active.name}」？这个配置里的服务地址、模型 ID、API Key 和高级设置都会删掉。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Grow()
            TextBtn("取消", enabled = !testing) { confirmDel = false }
            Btn("删除", danger = true, enabled = !testing) {
                confirmDel = false
                if (!prefs.deleteByokProfile(active)) {
                    byokResult.value = "⚠ key 或请求头没能从加密文件删掉，请再试一次；钥匙串要求授权时请选「允许」。"
                    return@Btn
                }
                byokDraft.value = ByokDraft(); byokAdvanced.value = null
                byokResult.value = "已切换到「${prefs.byokProfile.name}」，字幕从下一句开始用这个配置。".takeIf { running && prefs.byokReady }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Hint("填一个 OpenAI 兼容接口的 LLM 服务来翻译字幕，只发送识别出的文字，不发送声音。\n模型要求：必须能关闭思考（深度思考 / reasoning），否则每句都要等很久；尽量选回复速度快的模型，字幕才跟得上。")
    val d = byokDraft.value
    val saved = prefs.byokKey?.takeUnless { d.clearKey }
    var msg by byokResult
    Label("服务地址（OpenAI 兼容）")
    Field(d.url, { d.url = it }, "例如 https://api.deepseek.com，结尾有没有 /v1 都可以", enabled = !busy)
    Label("模型 ID")
    Field(d.model, { d.model = it }, "例如 deepseek-chat", enabled = !busy)
    Label(when {
        saved != null -> "API Key：已保存（…${saved.takeLast(4)}）"
        d.clearKey -> "API Key：保存后清除（不需要 key 的服务可以空着）"
        else -> "API Key：没填（不需要 key 的服务可以空着）"
    })
    Field(d.key, { d.key = it }, if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key", password = true, enabled = !busy)
    // save what is in the boxes; null when something is wrong (and says what)
    fun save(): String? {
        val u = d.url.trim(); val m = d.model.trim(); val k = d.key.trim()
        val (bad, _) = ByokTranslator.chatUrl(u)
        when {
            bad != null -> { msg = "⚠ $bad"; return null }
            m.isEmpty() -> { msg = "⚠ 还没填模型 ID"; return null }
            m.any { it.isWhitespace() } -> { msg = "⚠ 模型 ID 里不要有空格"; return null }
            k.isNotEmpty() && !Jev.validKey(k) -> { msg = "⚠ key 里有空格、换行或特殊字符，请重新复制"; return null }
        }
        // one edit; a running session switches to the saved service from the next sentence, as one change
        if (!prefs.saveByok(u, m, k.ifEmpty { if (d.clearKey) "" else null }, d.userAgent, d.headers)) {
            msg = "⚠ key 或请求头没能存进加密文件，请再试一次；钥匙串要求授权时请选「允许」。"; return null
        }
        byokDraft.value = ByokDraft()
        return ByokTranslator.chatUrl(u).second
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val extra = listOfNotNull((d.userAgent ?: prefs.byokUserAgent).ifEmpty { null }?.let { "自定义 User-Agent" },
            (d.headers ?: prefs.byokHeaders).size.takeIf { it > 0 }?.let { "$it 个请求头" },
            "未保存".takeIf { d.userAgent != null || d.headers != null }).joinToString("、")
        TextBtn("高级设置" + if (extra.isEmpty()) "" else "（$extra）", enabled = !busy && byokAdvanced.value == null) { byokAdvanced.value = ByokAdvancedDraft(d) }
        Grow()
        // running: "保存" switches the session from the next sentence (it tests a new model itself), so no test
        // (two probes would bill twice); clearing the key is a draft change like the rest, saved by 保存
        TextBtn("清除 key", enabled = saved != null && !busy) { d.clearKey = true; d.key = "" }
        Btn("保存", enabled = !busy) {
            if (save() != null) msg = if (running) "已保存，字幕从下一句开始用新的设置；第一次用这个模型时会先自动测试怎样关闭思考。" else null
        }
        Btn(if (testing) "测试中…" else "测试连接", primary = true, enabled = !running && !busy) {
            val chatUrl = save() ?: return@Btn
            val m = prefs.byokModel; val service = prefs.byokService() ?: return@Btn
            val t = ByokTranslator(service, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null,
                maxTokens = mtMaxTokens(prefs.targetLang))
            byokTest.value = t; msg = null
            Thread {
                val steps = mutableListOf<ByokTranslator.Step>()
                val v = t.probe(steps)
                java.awt.EventQueue.invokeLater {
                    if (byokTest.value !== t) return@invokeLater // cancelled (captions started)
                    byokTest.value = null
                    val ok = steps.firstOrNull { it.variant === v }?.result
                    val none = v == null && t.exhausted(steps)
                    when {
                        v != null -> prefs.setByokVariant(chatUrl, m, v.name)
                        none -> prefs.setByokVariant(chatUrl, m, ByokTranslator.NONE_WORKED)
                    }
                    byokResult.value = when {
                        ok != null -> "✓ 可以用。参数：${v!!.label}（这次没检测到思考，已记住）；收到第一个字 ${ok.firstContentMs ?: "?"} ms" +
                            (if ((ok.firstContentMs ?: 0) > FIRST_CONTENT_MS) "（超过 ${FIRST_CONTENT_MS / 1000} 秒，翻译时这句会判为失败）" else "") + "。\n测试译文：${ok.text.take(60)}"
                        none -> "⚠ 连得上，但试过的 ${steps.size} 种参数都没能关掉思考，翻译可能很慢或经常失败。仍然可以使用。"
                        else -> "✗ 不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}"
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }
    val chat = ByokTranslator.chatUrl(prefs.byokUrl).second
    val known = chat?.let { prefs.byokVariant(it, prefs.byokModel) }
    Hint(msg ?: when {
        testing -> "正在依次尝试关闭思考的参数（最多 6 次，每次最多 8 秒，每次都会计费）…"
        chat == null || prefs.byokModel.isEmpty() -> "填好地址、模型 ID 和 key 后点「测试连接」。第一次使用时会依次试最多 6 种关闭思考的参数（每种是一次计费请求），找到没检测到思考的就记住。"
        known == ByokTranslator.NONE_WORKED -> "⚠ 上次测试：已知的参数都关不掉这个模型的思考，翻译可能很慢。仍然可以使用。"
        known != null -> "上次测试可用（用的参数：${ByokTranslator.variant(known)?.label}，那次没检测到思考）。实际请求地址：$chat"
        else -> "还没测试，第一次开始字幕时会自动测。实际请求地址：$chat"
    })
}

// ---- 页面 --------------------------------------------------------------------------------------------------

@Composable
private fun PageTab() {
    Group("页面颜色") {
        Hint("这个窗口用白天还是夜间颜色。不影响悬浮字幕，字幕颜色在「字幕」里选。")
        Seg(listOf("system" to "跟随系统", "light" to "白天", "dark" to "夜间"), prefs.theme) { prefs.theme = it }
    }
}

// ---- 记录 --------------------------------------------------------------------------------------------------

@Composable
private fun LogsTab() {
    var tick by remember { mutableIntStateOf(0) } // relists after a change, and every few seconds (a session may end)
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    Group("使用记录") {
        SwitchRow("记录每次使用", "打开后，每次开始字幕会在电脑上留一份文字记录：识别结果、翻译的提示词和译文、在线 Decisions 模型辅助的请求和回答，不含声音和 key。只有你自己发出去才会离开电脑，出问题时可以发给开发者帮助排查。从下次开始字幕起生效。",
            prefs.keepLogs) { prefs.keepLogs = it }
        SwitchRow("同时保留音频", "默认关闭；只在上面「记录每次使用」打开时才生效。把每次字幕采集到的声音和文字记录一起存成一份 WAV，只保存在本机、不会上传，每分钟约 1.9 MB；总量超过 200 MB 时自动删除最旧的完整录音。从下次开始字幕起生效。",
            prefs.recordAudio) { prefs.recordAudio = it }
        tick.let { }
        // a killed session leaves a headerless recording: finish it here, so the list never offers an unplayable file
        AudioRecorder.recover(SessionLog.dir(dataDir))
        val files = SessionLog.list(dataDir)
        var confirm by remember { mutableStateOf<File?>(null) }
        var confirmAll by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        if (files.isEmpty()) Hint(if (prefs.keepLogs) "还没有记录。" else "还没有记录。现在不记录，打开上面的开关后才会记录。")
        for (f in files) {
            Divider()
            val active = SessionLog.isActive(f)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name.removeSuffix(".part").removeSuffix(".jsonl"), fontSize = 14.sp)
                    // the recording belongs to the record: its size is shown next to the log's (null: no audio was kept)
                    Text(sizeText(f.length()) + (SessionFiles.audioOf(f)?.let { " · 音频 " + sizeText(it.length()) } ?: "") +
                        when { active -> " · 正在记录，结束后可以发送"; !SessionLog.complete(f) -> " · 没有正常结束"; else -> "" },
                        fontSize = 12.sp, color = C.muted)
                }
                if (confirm == f) {
                    TextBtn("取消") { confirm = null }
                    TextBtn("删除", danger = true) { confirm = null; if (!SessionFiles.deleteLog(f)) msg = "删除失败：记录正在写入或文件被占用"; tick++ }
                } else {
                    TextBtn("删除", enabled = !active) { confirm = f }
                    TextBtn("在文件夹中显示", enabled = !active) { reveal(f) }
                }
            }
        }
        msg?.let { Hint(it, C.err) }
        if (files.isNotEmpty()) {
            Divider()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // the total counts a record's log and recording together (core counts the bytes, the text is ours)
                Text("共 ${files.size} 个，${sizeText(SessionFiles.totalBytes(dataDir))}", fontSize = 13.sp, color = C.muted)
                Grow()
                if (confirmAll) {
                    TextBtn("取消") { confirmAll = false }
                    TextBtn("删除全部（正在记录的不删）", danger = true) {
                        confirmAll = false
                        val left = SessionFiles.deleteAll(dataDir)
                        msg = if (left > 0) "还剩 $left 个（正在记录或无法删除）" else null; tick++
                    }
                } else TextBtn("全部清空", danger = true) { confirmAll = true }
            }
        }
    }
}


// ---- 关于 --------------------------------------------------------------------------------------------------

@Composable
private fun AboutSection() {
    Group("关于") {
        Text("同声传译（diy版）  版本 $APP_VERSION", fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
        Divider()
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (update.value is Update.Checking) "检查中…" else "检查更新", enabled = update.value !is Update.Checking,
                modifier = Modifier.weight(1f)) { checkNow(manual = true) }
            Btn("打开下载页", modifier = Modifier.weight(1f)) { open("$REPO/releases/tag/diy-latest") }
        }
        Hint("桌面端不会自动安装新版：点「打开下载页」下载 Windows-SimulSub-x64.zip，退出本程序后解压替换即可，设置和 key 都保留。GitHub 项目主页：$REPO")
        when (val u = update.value) {
            is Update.Found -> UpdateBox(u.release)
            is Update.Latest -> Hint(if (LOCAL_COMMIT.isEmpty()) "已经是最新版本。" else "已经是最新版本（本机构建 ${LOCAL_COMMIT.take(7)}，和线上一致）。")
            is Update.Failed -> Hint("检查失败：${u.why}", C.err)
            else -> {}
        }
        Hint("语音识别用的是 SenseVoice 模型和 Silero VAD（通过 sherpa-onnx 在电脑上运行，模型已经装在 App 里，不用额外下载）。感谢这些项目。")
    }
}

// ---- updates -----------------------------------------------------------------------------------------------

/** The latest release as update.json describes it; the desktop only needs its version, notes and commit. */
private class Release(val code: Int, val name: String, val notes: String, val commit: String)

/** DIY: 这次构建的提交号（CI 写进桌面资源），用来判断线上是不是同一个版本；从源码直接跑时为空。 */
private val LOCAL_COMMIT: String by lazy {
    runCatching {
        Thread.currentThread().contextClassLoader.getResourceAsStream("build-commit.txt")
            ?.bufferedReader()?.use { it.readText().trim() } ?: ""
    }.getOrDefault("")
}

private sealed class Update {
    object Idle : Update(); object Checking : Update(); object Latest : Update()
    class Found(val release: Release) : Update(); class Failed(val why: String) : Update()
}
private val update = mutableStateOf<Update>(Update.Idle)
/** a newer release found by the automatic check, shown as a window until the user decides */
private val offered = mutableStateOf<Release?>(null)

private val UPDATE_CLIENT = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

/** update.json from GitHub, then the download proxies (same order as the phone); null with the last error otherwise */
private fun fetchRelease(): Pair<Release?, String?> {
    var why: String? = null
    val latest = "$REPO/releases/latest/download/update.json"
    for (u in listOf(latest, "https://gh-proxy.com/$latest", "https://gh.llkk.cc/$latest")) {
        try {
            UPDATE_CLIENT.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (!r.isSuccessful) { why = "服务器返回 ${r.code}"; return@use }
                val text = r.body!!.byteStream().use { it.readNBytes(128 * 1024 + 1) }
                require(text.size <= 128 * 1024)
                val j = JSONObject(String(text))
                val code = j.get("versionCode").toString().toInt()
                val name = j.getString("versionName")
                require(code > 0 && name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9._-]{1,24})?")))
                return Release(code, name, j.optString("notes").take(8_000), j.optString("commit")) to null
            }
        } catch (e: Exception) { why = e.message ?: e.javaClass.simpleName }
    }
    return null to (why ?: "没有回应")
}

private fun checkNow(manual: Boolean) {
    if (update.value is Update.Checking) return
    update.value = Update.Checking
    Thread {
        val (r, why) = fetchRelease()
        java.awt.EventQueue.invokeLater {
            update.value = when {
                r == null -> if (manual) Update.Failed(why ?: "") else Update.Idle
                // DIY: 桌面端用提交号判断——线上就是自己这次构建，说明已是最新
                LOCAL_COMMIT.isNotEmpty() && r.commit.equals(LOCAL_COMMIT, ignoreCase = true) ->
                    if (manual) Update.Latest else Update.Idle
                r.code > APP_VERSION_CODE -> Update.Found(r)
                else -> if (manual) Update.Latest else Update.Idle
            }
            if (!manual && r != null && r.code > APP_VERSION_CODE) { /* DIY: 桌面端不自更新，启动时不弹窗 */ }
        }
    }.apply { isDaemon = true }.start()
}

@Composable
private fun UpdateBox(r: Release) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(C.brandSoft, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text("线上有新版本 ${r.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (r.notes.isNotBlank()) Text(r.notes, fontSize = 13.sp, lineHeight = 20.sp, color = C.text2, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("打开下载页", primary = true) { open("$REPO/releases/tag/diy-latest") }
        }
        Hint("下载页里「Windows-SimulSub-x64.zip」是 Windows 版。先退出本程序，解压后用新的文件夹替换旧的，设置会保留（设置存在 %APPDATA%\\SimulSub，不随程序目录走）。")
    }
}

/** Checks once at launch when automatic checks are on; a newer release opens a small window. */
@Composable
private fun ApplicationScope.AutoUpdateCheck() {
    LaunchedEffect(Unit) { if (prefs.autoUpdate && APP_VERSION_CODE > 0) { delay(3_000); checkNow(manual = false) } }
    val r = offered.value ?: return
    val dark = when (prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    Window(onCloseRequest = { offered.value = null }, title = "同声传译（diy版）有新版本", state = rememberWindowState(size = DpSize(440.dp, 360.dp))) {
        Themed(dark) {
            Column(Modifier.fillMaxSize().background(C.bg).padding(18.dp).verticalScroll(rememberScrollState())) {
                UpdateBox(r)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Grow()
                    TextBtn("跳过这个版本") { prefs.skippedUpdate = r.code; offered.value = null }
                    TextBtn("以后再说") { offered.value = null }
                }
            }
        }
    }
}
