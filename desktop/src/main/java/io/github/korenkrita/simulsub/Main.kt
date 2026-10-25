package io.github.korenkrita.simulsub

import java.io.File

/** Same version as the Android app of this release (one GitHub Release carries the APK and the zip). */
val APP_VERSION: String = System.getProperty("simulsub.version") ?: "dev"
val APP_VERSION_CODE: Int = System.getProperty("simulsub.versionCode")?.toIntOrNull() ?: 0

/**
 * The speech models (asr/, vad/): inside the packaged app (its resources, put there by tools/win-app), or
 * where -Dsimulsub.models points (./models when run from the repo, see ./setup.sh).
 */
val modelsDir: File = File(System.getProperty("simulsub.models")
    ?: File(System.getProperty("compose.application.resources.dir") ?: ".", "models").path)

/**
 * Whether the speech models the engine opens are all there: `asr_sv/model.int8.onnx`, `asr_sv/tokens.txt` and
 * `vad/silero_vad.onnx` (see Engines.kt). The packaged app ships them; a source run needs ./setup.sh first, and the
 * home page should say so instead of letting 开始字幕 fail with a stack trace.
 */
fun modelsReady(): Boolean =
    File(modelsDir, "asr_sv/model.int8.onnx").isFile &&
        File(modelsDir, "asr_sv/tokens.txt").isFile &&
        File(modelsDir, "vad/silero_vad.onnx").isFile

/** The audio helper: next to the app's resources in the package, or tools/win-app's build when run from the repo. */
val sysaudioFile: File = System.getProperty("compose.application.resources.dir")
    ?.let { File(it, "sysaudio.exe") }
    ?.takeIf { it.isFile }
    ?: File(System.getProperty("simulsub.root") ?: ".", "tools/win-app/build/resources/windows-x64/sysaudio.exe")

fun main() {
    ByokTranslator.defaultUserAgent = "SimulSub/$APP_VERSION"
    // the translation service, Jev and the update check go through the system's proxy, as a browser does (a proxy
    // app's "system proxy" mode; without it, some services refuse the direct connection, e.g. Jev with HTTP 451)
    // (-Djava.net.useSystemProxies=false in JAVA_TOOL_OPTIONS turns it off)
    if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true")
    // ...and straight to the service when the proxy fails it, as a PAC's "PROXY …; DIRECT" does: a proxy app may not
    // pass every host (the user's closed the TLS handshake to their own translation service). OkHttp tries the next
    // route when connecting or the handshake fails, before the request is sent, and tries the failed route last
    // afterwards.
    val system = java.net.ProxySelector.getDefault()
    java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI): List<java.net.Proxy> {
            val p = runCatching { system.select(uri) }.getOrNull().orEmpty()
            return if (p.isEmpty() || java.net.Proxy.NO_PROXY in p) p.ifEmpty { listOf(java.net.Proxy.NO_PROXY) }
                else p + java.net.Proxy.NO_PROXY
        }
        override fun connectFailed(uri: java.net.URI, sa: java.net.SocketAddress, e: java.io.IOException) =
            system.connectFailed(uri, sa, e)
    })
    // Windows: trust the certificates Windows trusts, as the browser does. A proxy app that re-encrypts HTTPS (an
    // "accelerator", a MITM rule) installs its root there; with Java's own list the handshake through it fails.
    if (System.getProperty("javax.net.ssl.trustStoreType") == null)
        System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
    // Windows: Direct3D (skiko's default there) draws the transparent caption window wrong: the toolbar is cut off
    // after a move, a larger card vanishes, and closing it hangs the app; OpenGL leaves the toolbar's bottom black.
    // Software rendering draws it right (tested on Windows 10/11). SKIKO_RENDER_API in the environment still wins.
    if (System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "SOFTWARE")
    // the packaged app: sherpa-onnx loads its native libraries from here
    System.getProperty("compose.application.resources.dir")?.let { System.setProperty("sherpa_onnx.native.path", it) }
    // quitting / SIGTERM: the helper only notices a lost reader when it next writes, so end it here (not via the UI
    // thread, which may already be gone)
    Runtime.getRuntime().addShutdownHook(Thread { CaptionService.killCapture() })
    app()
}
