package io.github.korenkrita.simulsub

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser

/**
 * A system-wide hotkey (Ctrl+Alt+T) that toggles the overlay's click-through.
 *
 * The reason it is global: with 穿透 on, the overlay swallows no clicks at all — not even the ones that would turn it
 * off — and the main window may already be closed. A hotkey is the way back that needs neither.
 *
 * Implemented with `RegisterHotKey(null, …)`: with no window handle, Windows posts `WM_HOTKEY` to the queue of the
 * registering thread, so a dedicated daemon thread registers once and then sits in `GetMessage`. JNA is already a
 * dependency (the registry, DPAPI and the window style above use it), so this adds no library.
 */
internal object OverlayHotkey {
    private const val ID = 0xB10
    private const val MOD_NOREPEAT = 0x4000

    /** the key that toggles 鼠标穿透: Ctrl + Alt + T */
    const val LABEL = "Ctrl + Alt + F9"

    @Volatile private var started = false

    fun start(onToggle: () -> Unit) {
        if (started) return
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        started = true
        Thread({
            val user32 = User32.INSTANCE
            val mods = User32.MOD_CONTROL or User32.MOD_ALT or MOD_NOREPEAT
            val ok = runCatching { user32.RegisterHotKey(null, ID, mods, 0x78 /* F9 */) }.getOrDefault(false)
            // a console-less launch has nowhere to print: leave the outcome where a support question can find it
            runCatching {
                java.io.File(System.getProperty("java.io.tmpdir"), "simulsub-hotkey.log")
                    .writeText(if (ok) "registered" else "failed")
            }
            if (!ok) { started = false; return@Thread }
            try {
                val msg = WinUser.MSG()
                while (user32.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WinUser.WM_HOTKEY) runCatching { onToggle() }
                }
            } finally {
                runCatching { user32.UnregisterHotKey(null, ID) }
            }
        }, "simulsub-hotkey").apply { isDaemon = true; start() }
    }
}
