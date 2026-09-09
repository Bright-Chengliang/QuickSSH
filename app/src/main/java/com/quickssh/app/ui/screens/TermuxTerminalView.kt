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

    var terminalViewInstance by remember { mutableStateOf<TerminalView?>(null) }

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

                setTextSize(fontSizePx)
                setTypeface(Typeface.MONOSPACE)
                setTerminalCursorBlinkerRate(500)
                setTerminalCursorBlinkerState(true, true)

                setOnClickListener {
                    requestFocus()
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                }

                var lastTouchY = 0f
                var accumulatedScrollDelta = 0f
                val scrollStepPx = 48f * ctx.resources.displayMetrics.density

                setOnTouchListener { v, event ->
                    val termView = v as? TerminalView
                    val em = termView?.mEmulator
                    val activeSession = termView?.currentSession ?: session
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastTouchY = event.y
                            accumulatedScrollDelta = 0f
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val currentY = event.y
                            val dy = currentY - lastTouchY
                            lastTouchY = currentY

                            val mouseTracking = em?.isMouseTrackingActive == true

                            // If mouse tracking is inactive (e.g. Windows ConPTY swallowed it or TUI without mouse mode),
                            // dispatch PgUp / PgDn on vertical swipe to scroll the TUI viewport.
                            if (!mouseTracking && activeSession != null) {
                                accumulatedScrollDelta += dy
                                while (accumulatedScrollDelta >= scrollStepPx) {
                                    activeSession.write("\u001B[5~") // PgUp: drag down -> scroll up
                                    accumulatedScrollDelta -= scrollStepPx
                                }
                                while (accumulatedScrollDelta <= -scrollStepPx) {
                                    activeSession.write("\u001B[6~") // PgDn: drag up -> scroll down
                                    accumulatedScrollDelta += scrollStepPx
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            accumulatedScrollDelta = 0f
                        }
                    }
                    false
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

                terminalViewInstance = this
            }
        },
        update = { view ->
            val currentTextSize = try {
                val rendererField = TerminalView::class.java.getDeclaredField("mRenderer").apply { isAccessible = true }
                val renderer = rendererField.get(view)
                val textSizeField = renderer.javaClass.getDeclaredField("mTextSize").apply { isAccessible = true }
                textSizeField.getInt(renderer)
            } catch (_: Exception) {
                -1
            }
            if (currentTextSize != fontSizePx) {
                view.setTextSize(fontSizePx)
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
        val colsField = em.javaClass.getDeclaredField("mColumns").apply { isAccessible = true }
        val rowsField = em.javaClass.getDeclaredField("mRows").apply { isAccessible = true }
        val cols = colsField.getInt(em)
        val rows = rowsField.getInt(em)
        if (cols > 0 && rows > 0) Pair(cols, rows) else null
    } catch (_: Exception) {
        null
    }
}

private fun bindSessionToView(
    view: TerminalView,
    session: TerminalSession,
    onTerminalResize: ((TerminalBuffer.Size) -> Unit)? = null
) {
    if (view.currentSession != session) {
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
