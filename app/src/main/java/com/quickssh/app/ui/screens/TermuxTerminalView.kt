package com.quickssh.app.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.quickssh.app.utils.TerminalBuffer
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

@Composable
fun TermuxTerminalComponent(
    session: TerminalSession?,
    fontSizeSp: Int,
    ctrlKeyActive: Boolean,
    altKeyActive: Boolean,
    onCtrlKeyConsumed: () -> Unit,
    onAltKeyConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    onSingleTap: () -> Unit = {},
    onTerminalResize: ((TerminalBuffer.Size) -> Unit)? = null
) {
    val density = LocalDensity.current

    val currentCtrlActive by rememberUpdatedState(ctrlKeyActive)
    val currentAltActive by rememberUpdatedState(altKeyActive)
    val currentOnCtrlConsumed by rememberUpdatedState(onCtrlKeyConsumed)
    val currentOnAltConsumed by rememberUpdatedState(onAltKeyConsumed)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnTerminalResize by rememberUpdatedState(onTerminalResize)

    val fontSizePx = with(density) { fontSizeSp.coerceIn(8, 32).sp.toPx() }.toInt()

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(object : TerminalViewClient {
                    override fun onScale(scale: Float): Float {
                        return scale
                    }

                    override fun onSingleTapUp(e: MotionEvent) {
                        requestFocus()
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                        currentOnSingleTap()
                    }

                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                    override fun shouldEnforceCharBasedInput(): Boolean = true
                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                    override fun isTerminalViewSelected(): Boolean = true
                    override fun copyModeChanged(copyMode: Boolean) {}
                    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
                    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
                    override fun onLongPress(event: MotionEvent): Boolean = false

                    override fun readControlKey(): Boolean {
                        if (currentCtrlActive) {
                            currentOnCtrlConsumed()
                            return true
                        }
                        return false
                    }

                    override fun readAltKey(): Boolean {
                        if (currentAltActive) {
                            currentOnAltConsumed()
                            return true
                        }
                        return false
                    }

                    override fun readShiftKey(): Boolean = false
                    override fun readFnKey(): Boolean = false
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
                    override fun onEmulatorSet() {}
                    override fun logError(tag: String, message: String) {}
                    override fun logWarn(tag: String, message: String) {}
                    override fun logInfo(tag: String, message: String) {}
                    override fun logDebug(tag: String, message: String) {}
                    override fun logVerbose(tag: String, message: String) {}
                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                    override fun logStackTrace(tag: String, e: Exception) {}
                })

                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()

                // The terminal owns vertical drags on its own (doScroll + fling). The touch listener below
                // only steps in when that path cannot move anything, so a swipe never scrolls twice.
                isVerticalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                var lastTouchY = 0f
                var accumulatedScrollDelta = 0f
                val scrollStepPx = 48f * ctx.resources.displayMetrics.density
                val scrollPager = TerminalScrollPager()

                setOnTouchListener { v, event ->
                    val termView = v as? TerminalView
                    val em = termView?.mEmulator
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastTouchY = event.y
                            accumulatedScrollDelta = 0f
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val currentY = event.y
                            val dy = currentY - lastTouchY
                            lastTouchY = currentY

                            // Termux scrolls its own transcript when real transcript rows exist. Windows
                            // ConPTY sessions keep them at zero, so a drag would be swallowed: only then
                            // translate the swipe into PgUp/PgDn.
                            if (termView != null && termuxNativeScrollIsIneffective(em)) {
                                var queuedPages = 0
                                accumulatedScrollDelta += dy
                                while (accumulatedScrollDelta >= scrollStepPx) {
                                    queuedPages++ // drag down -> scroll up
                                    accumulatedScrollDelta -= scrollStepPx
                                }
                                while (accumulatedScrollDelta <= -scrollStepPx) {
                                    queuedPages-- // drag up -> scroll down
                                    accumulatedScrollDelta += scrollStepPx
                                }
                                scrollPager.queue(queuedPages, termView)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            accumulatedScrollDelta = 0f
                        }
                    }
                    false
                }

                setTextSize(fontSizePx)
                setTypeface(Typeface.MONOSPACE)
                rememberTextSize(fontSizePx)
                setTerminalCursorBlinkerRate(500)
                setTerminalCursorBlinkerState(true, true)

                setOnClickListener {
                    requestFocus()
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                }

                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    val w = right - left
                    val h = bottom - top
                    if (w > 0 && h > 0 && (w != oldRight - oldLeft || h != oldBottom - oldTop)) {
                        post {
                            val activeSession = currentSession ?: session
                            if (activeSession != null) {
                                getEmulatorDimensions(activeSession)?.let { (cols, rows) ->
                                    currentOnTerminalResize?.invoke(TerminalBuffer.Size(cols, rows, w, h))
                                }
                            }
                        }
                    }
                }

                if (session != null) {
                    bindSessionToView(this, session, currentOnTerminalResize)
                }
            }
        },
        update = { view ->
            if (view.rendererTextSize() != fontSizePx) {
                view.setTextSize(fontSizePx)
                view.rememberTextSize(fontSizePx)
                view.post {
                    val activeSession = view.currentSession ?: session
                    if (activeSession != null) {
                        getEmulatorDimensions(activeSession)?.let { (cols, rows) ->
                            if (view.width > 0 && view.height > 0) {
                                currentOnTerminalResize?.invoke(TerminalBuffer.Size(cols, rows, view.width, view.height))
                            }
                        }
                    }
                }
            }
            if (session != null) {
                bindSessionToView(view, session, currentOnTerminalResize)
            }
        }
    )
}

/**
 * Renderer text size in px. TerminalView does not expose it, and every `setTextSize` rebuilds the
 * renderer and re-sizes the PTY, so it is remembered on the view instead of reflecting into it.
 */
private fun TerminalView.rendererTextSize(): Int = (tag as? Int) ?: -1

private fun TerminalView.rememberTextSize(textSizePx: Int) {
    tag = textSizePx
}

/**
 * True when a vertical drag cannot scroll the terminal on its own. Termux moves its own transcript only
 * when mouse tracking is off, the alternate buffer is off, and real transcript rows exist; a Windows
 * ConPTY session reports all three that way, so only then the swipe becomes PgUp/PgDn.
 */
internal fun termuxNativeScrollIsIneffective(
    mouseTrackingActive: Boolean?,
    alternateBufferActive: Boolean?,
    activeTranscriptRows: Int?
): Boolean {
    if (mouseTrackingActive == null || alternateBufferActive == null || activeTranscriptRows == null) {
        return false
    }
    if (mouseTrackingActive) return false
    if (alternateBufferActive) return false
    return activeTranscriptRows <= 0
}

private fun termuxNativeScrollIsIneffective(emulator: com.termux.terminal.TerminalEmulator?): Boolean {
    if (emulator == null) return false
    return runCatching {
        termuxNativeScrollIsIneffective(
            mouseTrackingActive = emulator.isMouseTrackingActive,
            alternateBufferActive = emulator.isAlternateBufferActive,
            activeTranscriptRows = emulator.screen.activeTranscriptRows
        )
    }.getOrDefault(false)
}


private val scrollWriteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "quickssh-terminal-scroll").apply { isDaemon = true }
}

/**
 * Coalesced PgUp/PgDn writer. Writing to the SSH stream can block on a slow link, so it never runs on the
 * UI thread. Pages are batched per flush window instead of one write per page: this fallback scroll is a
 * request/response exchange with the remote, so on a real network every single page costs a round trip and
 * a fast drag would stutter, while one write carrying N repeats costs the same single round trip.
 */
private class TerminalScrollPager {
    private val pendingPages = java.util.concurrent.atomic.AtomicInteger(0)
    private val writePending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun queue(pages: Int, view: TerminalView) {
        if (pages == 0) return
        pendingPages.addAndGet(pages)
        arm(view)
    }

    private fun arm(view: TerminalView) {
        if (!writePending.compareAndSet(false, true)) return
        view.postDelayed({ flush(view) }, SCROLL_FLUSH_DELAY_MS)
    }

    private fun flush(view: TerminalView) {
        scrollWriteExecutor.execute {
            try {
                while (true) {
                    val queued = pendingPages.getAndSet(0)
                    if (queued != 0) {
                        val session = view.currentSession ?: break
                        val pageSequence = if (queued > 0) "\u001B[5~" else "\u001B[6~"
                        runCatching { session.write(pageSequence.repeat(kotlin.math.abs(queued))) }
                        continue
                    }
                    // Release the slot, then re-check: a page queued between the empty read and this
                    // release must not be stranded until the next drag event.
                    writePending.set(false)
                    if (pendingPages.get() == 0) break
                    if (!writePending.compareAndSet(false, true)) break
                }
            } finally {
                writePending.set(false)
            }
        }
    }

    private companion object {
        /** One frame plus a little slack: enough to accumulate a whole drag step into one write. */
        const val SCROLL_FLUSH_DELAY_MS = 32L
    }
}




private class TermuxViewClientDelegate(
    val baseClient: TerminalSessionClient?,
    var view: TerminalView?
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
        baseClient?.onTextChanged(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        baseClient?.onTitleChanged(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        baseClient?.onSessionFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(changedSession: TerminalSession, text: String) {
        try {
            val ctx = view?.context ?: return
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        } catch (_: Exception) {}
        baseClient?.onCopyTextToClipboard(changedSession, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        try {
            val ctx = view?.context ?: return
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                session.write(text)
            }
        } catch (_: Exception) {}
        baseClient?.onPasteTextFromClipboard(session)
    }

    override fun onBell(session: TerminalSession) {
        baseClient?.onBell(session)
    }

    override fun onColorsChanged(session: TerminalSession) {
        view?.invalidate()
        baseClient?.onColorsChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        baseClient?.onTerminalCursorStateChange(state)
    }

    override fun getTerminalCursorStyle(): Int? = baseClient?.terminalCursorStyle
    override fun logError(tag: String, message: String) { baseClient?.logError(tag, message) }
    override fun logWarn(tag: String, message: String) { baseClient?.logWarn(tag, message) }
    override fun logInfo(tag: String, message: String) { baseClient?.logInfo(tag, message) }
    override fun logDebug(tag: String, message: String) { baseClient?.logDebug(tag, message) }
    override fun logVerbose(tag: String, message: String) { baseClient?.logVerbose(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        baseClient?.logStackTraceWithMessage(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception) {
        baseClient?.logStackTrace(tag, e)
    }
}

private fun getEmulatorDimensions(session: TerminalSession): Pair<Int, Int>? {
    return try {
        val em = session.emulator ?: return null
        val cols = em.mColumns
        val rows = em.mRows
        if (cols > 0 && rows > 0) Pair(cols, rows) else null
    } catch (_: Throwable) {
        null
    }
}

private fun bindSessionToView(
    view: TerminalView,
    session: TerminalSession,
    onTerminalResize: ((TerminalBuffer.Size) -> Unit)? = null
) {
    if (view.currentSession !== session) {
        view.attachSession(session)
        view.post {
            getEmulatorDimensions(session)?.let { (cols, rows) ->
                if (view.width > 0 && view.height > 0) {
                    onTerminalResize?.invoke(TerminalBuffer.Size(cols, rows, view.width, view.height))
                }
            }
        }
    }

    val currentClient = try {
        val clientField = TerminalSession::class.java.getDeclaredField("mClient").apply { isAccessible = true }
        clientField.get(session) as? TerminalSessionClient
    } catch (_: Exception) {
        null
    }

    if (currentClient is TermuxViewClientDelegate) {
        currentClient.view = view
    } else {
        session.updateTerminalSessionClient(TermuxViewClientDelegate(currentClient, view))
    }
}
