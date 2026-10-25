package io.github.korenkrita.simulsub

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND

/**
 * The one thing the overlay needs from Win32: making its window click-through, so the mouse reaches the video under
 * the captions instead of dragging them.
 *
 * `WS_EX_TRANSPARENT` lets the mouse fall through the window, `WS_EX_LAYERED` is what that flag needs to work on a
 * per-pixel window. Both live in the extended window style, so the flag is toggled by reading the style, OR-ing or
 * AND-ing it out, and writing it back. JNA is already a dependency (DPAPI and the registry use it), so this needs
 * no new library and no native code of our own.
 */
internal object WinOverlay {
    /** set [on] = false to give the window its mouse back; safe to call on any platform (a no-op off Windows) */
    fun setClickThrough(window: java.awt.Window, on: Boolean) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        runCatching {
            val hwnd = HWND(Native.getWindowPointer(window))
            if (hwnd == null) return
            val user32 = User32.INSTANCE
            val style = user32.GetWindowLong(hwnd, User32.GWL_EXSTYLE)
            val want = if (on) style or User32.WS_EX_LAYERED or User32.WS_EX_TRANSPARENT
                       else style and User32.WS_EX_TRANSPARENT.inv()
            user32.SetWindowLong(hwnd, User32.GWL_EXSTYLE, want)
        }
    }
}
