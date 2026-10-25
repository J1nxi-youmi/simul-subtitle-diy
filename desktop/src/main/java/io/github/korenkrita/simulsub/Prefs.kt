package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the app keeps its settings and logs: %APPDATA%\SimulSub on Windows (or -Dsimulsub.data).
 */
val dataDir: File = File(System.getProperty("simulsub.data")
    ?: "${System.getenv("APPDATA") ?: System.getProperty("user.home")}\\SimulSub").also { it.mkdirs() }

/**
 * The phone's settings with the same names and defaults, kept in settings.json; keys (Jev, BYOK) go to keys.json,
 * encrypted for this Windows user. Every read also reads [rev], so a composable that shows a setting follows its changes.
 * Listeners get the changed key on the thread that changed it, like SharedPreferences.
 */
class Prefs internal constructor(private val file: File) : SessionPrefs {
    private val lock = Any()
    private val json: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private val rev = mutableIntStateOf(0)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private fun <T> get(read: JSONObject.() -> T): T { rev.intValue; return synchronized(lock) { json.read() } }
    private fun put(vararg kv: Pair<String, Any?>) {
        synchronized(lock) {
            for ((k, v) in kv) if (v == null) json.remove(k) else json.put(k, v)
            // whole-file replace: a crash mid-write never leaves half a settings file
            val tmp = File(file.path + ".tmp")
            // File.renameTo does not replace an existing file on Windows: every save after the first was lost there
            runCatching {
                tmp.writeText(json.toString(1))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { System.err.println("settings: not saved: $it") }
        }
        rev.intValue++
        for ((k, _) in kv) listeners.forEach { it(k) }
    }
    private fun float(k: String, d: Float) = get { optDouble(k, d.toDouble()).toFloat() }
    private fun int(k: String, d: Int) = get { optInt(k, d) }
    private fun bool(k: String, d: Boolean) = get { optBoolean(k, d) }
    private fun str(k: String): String? = get { if (has(k)) optString(k) else null }

    var textSp: Float get() = float("textSp", 13f); set(v) = put("textSp" to v)
    var bgAlpha: Float get() = float("bgAlpha", DEFAULT_ALPHA); set(v) = put("bgAlpha" to v)
    fun setStyle(sizeSp: Float, alpha: Float) = put("textSp" to sizeSp, "bgAlpha" to alpha)
    /** page colours: "system", "light" or "dark" */
    var theme: String get() = str("theme") ?: "system"; set(v) = put("theme" to v)
    var palette: String get() = str("palette") ?: PALETTES[0].id; set(v) = put("palette" to v)
    /** the old width setting: a part of the screen width; only read to migrate a pre-pixel value */
    var widthFrac: Float get() = float("widthFrac", DEFAULT_WIDTH); set(v) = put("widthFrac" to v)
    /** caption card width in pixels; 0 = never set, so [overlayWidth] falls back to [widthFrac] once */
    var widthPx: Int get() = int("widthPx", 0); set(v) = put("widthPx" to v)
    /**
     * The caption card width on a screen [screenWidth] wide. Pixels are what the user picks now; a settings file
     * written before that (only [widthFrac]) keeps its look by converting once, and the next drag stores pixels.
     */
    fun overlayWidth(screenWidth: Int): Int {
        val px = widthPx
        if (px > 0) return px.coerceIn(MIN_WIDTH_PX, screenWidth)
        return Math.round(screenWidth * widthFrac).coerceIn(MIN_WIDTH_PX, screenWidth)
    }
    /** whether the caption overlay is on screen at all (「悬浮字幕」) */
    var overlayShown: Boolean get() = bool("overlayShown", true); set(v) = put("overlayShown" to v)
    /** whether the overlay stays above other windows (「置顶」) */
    var overlayOnTop: Boolean get() = bool("overlayOnTop", true); set(v) = put("overlayOnTop" to v)
    /** whether clicks fall through the overlay to the video under it (「鼠标穿透」) */
    var overlayClickThrough: Boolean get() = bool("overlayClickThrough", false); set(v) = put("overlayClickThrough" to v)
    /** caption area height as a part of the screen height; 0 = follows the content */
    var heightFrac: Float get() = float("heightFrac", 0f); set(v) = put("heightFrac" to v)
    override var showJa: Boolean get() = bool("showJa", true); set(v) = put("showJa" to v)
    override var showStats: Boolean get() = bool("showStats", true); set(v) = put("showStats" to v)
    /** the card's top-left in points, on any display (left of or above the main one is negative); null = not placed yet */
    val overlayX: Int? get() = get { if (has("overlayX")) optInt("overlayX") else null }
    val overlayY: Int? get() = get { if (has("overlayY")) optInt("overlayY") else null }
    /** the card's bottom edge when it was placed, in the same points as [overlayY] */
    val overlayBottom: Int? get() = get { if (has("overlayBottom")) optInt("overlayBottom") else null }
    fun setOverlay(x: Int?, y: Int?, bottom: Int?, widthFrac: Float, heightFrac: Float) =
        put("overlayX" to x, "overlayY" to y, "overlayBottom" to bottom, "widthFrac" to widthFrac, "heightFrac" to heightFrac)
    override var keepSentences: Int get() = int("keepSentences", CaptionBoard.DEFAULT_SENTENCES); set(v) = put("keepSentences" to v)
    override var keepChars: Int get() = int("keepChars", CaptionBoard.DEFAULT_CHARS); set(v) = put("keepChars" to v)
    // DIY: 断句参数和手机端共用同一组键，默认值也和手机端一致
    override val endSilenceMs: Int get() = int("endSilenceMs", 350)
    override val maxUtteranceMs: Int get() = int("maxUtteranceMs", 6_000)
    var endSilenceSetting: Int get() = endSilenceMs
        set(v) = put("endSilenceMs" to v.coerceIn(100, 1_200))
    var maxUtteranceSetting: Int get() = maxUtteranceMs
        set(v) = put("maxUtteranceMs" to v.coerceIn(1_500, 12_000))
    /** DIY: 译文检查用的模型；空 = 不检查 */
    override val judgeModel: String get() = str("judgeModel") ?: ""
    /** DIY: 校验单独的接口；留空即复用翻译那套 */
    override val judgeUrl: String get() = str("judgeUrl") ?: ""
    var judgeModelSetting: String get() = judgeModel; set(v) = put("judgeModel" to v.trim())
    var judgeUrlSetting: String get() = judgeUrl; set(v) = put("judgeUrl" to v.trim())
    override fun judgeKey(): String? = Keychain.get("judgeKey")
    val hasJudgeKey: Boolean get() = Keychain.get("judgeKey") != null
    fun setJudgeKey(key: String?): Boolean =
        Keychain.set("judgeKey", key?.trim()).also { if (it) put("judgeKey" to null) }
    /** DIY: 识别语种，和手机端共用同一个键（默认日语） */
    val asrSvLang: String get() = str("asrSvLang") ?: "ja"
    var asrSvLangSetting: String get() = asrSvLang; set(v) = put("asrSvLang" to v)
    override var finalOnly: Boolean get() = bool("finalOnly", false); set(v) = put("finalOnly" to v)
    /** null: not sure (the recognizer decides) */
    override var sourceLang: Lang?
        get() = (str("sourceLang") ?: "ja").let { if (it == "auto") null else Lang.of(it) ?: Lang.JA }
        set(v) = put("sourceLang" to (v?.code ?: "auto"))
    /** a preset language, or one the user typed ("custom:<name>") */
    override var targetLang: Target get() = Target.parse(str("targetLang")) ?: Target.ZH; set(v) = put("targetLang" to v.stored)
    /** The built-in scenes (as edited) and the user's own, see [Scene.all]. */
    var scenes: List<Scene> get() = Scene.all(str("scenes")); set(v) = put("scenes" to Scene.store(v))
    /** one of [scenes] */
    override var scene: Scene
        get() = str("scene").let { id -> scenes.let { all -> all.firstOrNull { it.id == id } ?: all.first() } }
        set(v) = put("scene" to v.id)
    override var jevOn: Boolean get() = bool("jevOn", false); set(v) = put("jevOn" to v)
    override var jevCheck: Boolean get() = bool("jevCheck", false); set(v) = put("jevCheck" to v)
    override var dedupeJoin: Boolean get() = bool("dedupeJoin", false); set(v) = put("dedupeJoin" to v)
    override var keepLogs: Boolean get() = bool("keepLogs", false); set(v) = put("keepLogs" to v)
    override var recordAudio: Boolean get() = bool("recordAudio", false); set(v) = put("recordAudio" to v)
    override var contextOn: Boolean get() = bool("contextOn", true); set(v) = put("contextOn" to v)
    override var contextSentences: Int
        get() = int("contextSentences", Pipeline.PREVIOUS_SENTENCES).coerceIn(1, Pipeline.MAX_PREVIOUS_SENTENCES)
        set(v) = put("contextSentences" to v)
    override var jevProvider: JevProvider
        get() = runCatching { JevProvider.valueOf(str("jevProvider") ?: "") }.getOrDefault(JevProvider.ZEN)
        set(v) = put("jevProvider" to v.name)
    var autoUpdate: Boolean get() = bool("autoUpdate", true); set(v) = put("autoUpdate" to v)
    /** a release the user chose to skip (its versionCode) */
    var skippedUpdate: Int get() = int("skippedUpdate", 0); set(v) = put("skippedUpdate" to v)


    override fun jevKey(p: JevProvider): String? { rev.intValue; return Keychain.get("jevKey:${p.name}") }
    /** false when the keychain refused it */
    fun setJevKey(p: JevProvider, key: String?): Boolean =
        Keychain.set("jevKey:${p.name}", key?.trim()).also { if (it) put("jevKey:${p.name}" to null) }

    /**
     * BYOK: the user's OpenAI-compatible services, saved as [LlmProfile]s; the one in use is byokActive. Each one's
     * fields are the storage names plus its suffix (the first one: the names from before). Keys in the keychain.
     */
    val byokProfiles: List<LlmProfile> get() = LlmProfile.all(str("byokProfiles"))
    val byokProfile: LlmProfile get() = LlmProfile.active(byokProfiles, str("byokActive"))
    private val sfx: String get() = byokProfile.suffix
    val byokUrl: String get() = str("byokUrl$sfx").orEmpty()
    val byokModel: String get() = str("byokModel$sfx").orEmpty()
    val byokKey: String? get() { rev.intValue; return Keychain.get("byokKey$sfx") }
    /** BYOK's fields and key change together: a running session must never pair a new key with an old address */
    private val byokLock = Any()
    /**
     * "保存": the fields, then byokSaved, so a running session never takes a half-saved set. False when the keychain
     * refused the key or the headers; then nothing else is saved and the session keeps the old set. [key] null keeps
     * the saved one; [headers] null keeps the saved ones (stored with the key: they may hold one).
     */
    fun saveByok(url: String, model: String, key: String?, userAgent: String? = null,
                 headers: List<Pair<String, String>>? = null): Boolean = synchronized(byokLock) {
        val s = sfx
        if (key != null && !Keychain.set("byokKey$s", key.trim().ifEmpty { null })) return false
        if (headers != null && !Keychain.set("byokHeaders$s", if (headers.isEmpty()) null else ByokService.storeHeaders(headers))) return false
        put("byokUrl$s" to url.trim(), "byokModel$s" to model.trim())
        if (userAgent != null) put("byokUserAgent$s" to userAgent.trim().ifEmpty { null })
        put("byokSaved" to System.nanoTime())
        true
    }
    private fun ready(s: String) =
        ByokTranslator.chatUrl(str("byokUrl$s") ?: "").second != null && !str("byokModel$s").isNullOrEmpty()
    /** use [p] from now on (a running session: from the next sentence, when [p] is filled in) */
    fun useByokProfile(p: LlmProfile) = synchronized(byokLock) {
        if (ready(p.suffix)) put("byokActive" to p.id, "byokSaved" to System.nanoTime()) else put("byokActive" to p.id)
    }
    /** a new, empty profile named [name] (checked by [LlmProfile.name]), put in use */
    fun addByokProfile(name: String): LlmProfile = synchronized(byokLock) {
        val list = byokProfiles
        val p = LlmProfile(LlmProfile.newId(list), name)
        put("byokProfiles" to LlmProfile.store(list + p), "byokActive" to p.id)
        p
    }
    fun renameByokProfile(p: LlmProfile, name: String) = synchronized(byokLock) {
        put("byokProfiles" to LlmProfile.store(byokProfiles.map { if (it.id == p.id) it.copy(name = name) else it }))
    }
    /** [p] and its fields (never the last one); the one in use: the first left takes over. False when the keychain refused. */
    fun deleteByokProfile(p: LlmProfile): Boolean = synchronized(byokLock) {
        val left = byokProfiles.filter { it.id != p.id }.ifEmpty { return false }
        val s = p.suffix
        if (!Keychain.set("byokKey$s", null) || !Keychain.set("byokHeaders$s", null)) return false
        val wasActive = byokProfile.id == p.id
        val kv = mutableListOf<Pair<String, Any?>>("byokUrl$s" to null, "byokModel$s" to null, "byokUserAgent$s" to null,
            "byokProfiles" to LlmProfile.store(left))
        if (wasActive) {
            kv += "byokActive" to left.first().id
            if (ready(left.first().suffix)) kv += "byokSaved" to System.nanoTime()
        }
        put(*kv.toTypedArray())
        true
    }
    val byokUserAgent: String get() = str("byokUserAgent$sfx").orEmpty()
    val byokHeaders: List<Pair<String, String>> get() { rev.intValue; return ByokService.parseHeaders(Keychain.get("byokHeaders$sfx")) }
    /** The saved service read as one (null: the address is not valid); the key may be "" (a service without one). */
    override fun byokService(): ByokService? = synchronized(byokLock) {
        val s = LlmProfile.active(LlmProfile.all(str("byokProfiles")), str("byokActive")).suffix
        val url = ByokTranslator.chatUrl(str("byokUrl$s") ?: "").second ?: return null
        ByokService(url, str("byokModel$s") ?: "", Keychain.get("byokKey$s").orEmpty(), str("byokUserAgent$s") ?: "",
            ByokService.parseHeaders(Keychain.get("byokHeaders$s")))
    }
    override fun byokVariant(url: String, model: String): String? = str("byokVariant:$url|$model")
    override fun setByokVariant(url: String, model: String, name: String?) = put("byokVariant:$url|$model" to name)
    /** address and model are there (the key may be left empty, for a free service) */
    val byokReady: Boolean get() = ByokTranslator.chatUrl(byokUrl).second != null && byokModel.isNotEmpty()

    fun listen(l: (String) -> Unit) = listeners.add(l)
    fun unlisten(l: (String) -> Unit) = listeners.remove(l)

    /** Back to the defaults of the caption look (「恢复默认」 on 字幕). */
    fun resetStyle() = put("textSp" to null, "bgAlpha" to null, "palette" to null, "widthFrac" to null, "widthPx" to null, "heightFrac" to null,
        "showJa" to null, "showStats" to null, "keepSentences" to null, "keepChars" to null)

    companion object {
        const val DEFAULT_WIDTH = 0.90f
        // 0.40 on the phone; a desk screen has room for a narrower card (about 300 points on a 1512-wide display)
        const val MIN_WIDTH = 0.20f
        /** the narrowest card worth rendering: about a danmaku panel, 300 px on a 1080p screen */
        const val MIN_WIDTH_PX = 240
        const val DEFAULT_ALPHA = 0.78f
        val prefs by lazy { Prefs(File(dataDir, "settings.json")) }
    }
}

/**
 * Secrets for the desktop app, cached: [Prefs] reads keys on every setup. On Windows they go to [Dpapi]
 * (keys.json in [dataDir], encrypted for the current user); this fork no longer has a macOS keychain path.
 */
object Keychain {
    /** Tests point this at a service of their own; Windows keys are per user, so the name only documents the owner. */
    internal var service: String = "SimulSub"
    private val cache = HashMap<String, String?>()

    @Synchronized fun get(account: String): String? = cache.getOrPut(account) { Dpapi.get(account) }

    /** true when the store took it (deleting a key that is not there counts as done) */
    @Synchronized fun set(account: String, value: String?): Boolean {
        cache.remove(account)
        return Dpapi.set(account, value?.ifEmpty { null }).also { if (it) cache[account] = value?.ifEmpty { null } }
    }
}

/**
 * Windows' place for [Keychain]: keys.json in [dataDir], each value encrypted with DPAPI for the Windows user (only
 * this user on this computer can read it back). Written whole to a temporary file, then moved over the old one.
 */
private object Dpapi {
    private val file get() = File(dataDir, "keys.json")

    private fun all(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    fun get(account: String): String? = runCatching {
        val b64 = all().optString(account).ifEmpty { return null }
        String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(b64)), Charsets.UTF_8)
    }.getOrNull()

    fun set(account: String, value: String?): Boolean = runCatching {
        val o = all()
        if (value == null) o.remove(account)
        else o.put(account, Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))))
        val tmp = File(dataDir, "keys.json.part")
        tmp.writeText(o.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)
}
