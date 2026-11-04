package com.lsp.hypersidebar.hook

/** A late MOVE/UP from another gesture must never show or launch the current menu. */
class CornerGestureSession {
    private var downTime = 0L
    private var cancelled = false
    private var opened = false
    fun begin(id: Long) { downTime = id; cancelled = false; opened = false }
    fun isCurrent(id: Long): Boolean = id > 0 && id == downTime && !cancelled
    fun shouldShow(id: Long, inputOwned: Boolean): Boolean {
        if (!isCurrent(id) || opened || !inputOwned) return false
        opened = true
        return true
    }
    fun cancel(id: Long) { if (id == downTime) cancelled = true }
    fun finish(id: Long) { if (id == downTime) begin(0L) }
}
