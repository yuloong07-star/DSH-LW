package io.github.miuzarte.littlewhale.ui

import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * What the back button does while the home page is the only page left
 *
 * This app is a host for an agent and is meant to keep running while it is out of sight, so the
 * back button does not end anything: the first press says how to leave and the second one, while
 * that message is still up, puts the task behind whatever the person was doing. Nothing is finished
 * and no service is stopped, so coming back from the launcher or from recents shows the same session
 * with its screens still running
 *
 * Only the home page reaches here. One page in, back is a pop and the navigator never asks this
 */
object ExitHint {

    /**
     * How long the message stays, in milliseconds
     *
     * Longer than the library's own short step, because this one names an action rather than
     * reporting something that already happened: the second press has to land while it is readable
     */
    const val VISIBLE_MS = 2_000L

    /**
     * Where the message goes
     *
     * Held here rather than remembered in the composition because the back callback is a plain
     * lambda: showing happens in the home page, asking for it happens outside a composition
     */
    val host = SnackbarHostState()

    /** A press only counts as the second one inside this window, in milliseconds */
    private const val WINDOW_MS = 2_500L

    /** When the last press happened, or 0 when there has not been one yet */
    private var lastPress = 0L

    /** When the message that is up right now was asked for, so it is only asked for once */
    private var hintedAt = 0L

    /**
     * One press of the back button on the home page
     *
     * Answers whether the message was already up, which is the caller's cue to leave rather than to
     * ask again: asking again would only replace the message the person is still looking at
     */
    fun press(): Boolean {
        val now = System.currentTimeMillis()
        if (lastPress != 0L && now - lastPress < WINDOW_MS) {
            lastPress = 0L
            return true
        }
        lastPress = now
        return false
    }

    /**
     * Whether the message has to be put up now
     *
     * Kept apart from [press] because showing it is the part that needs a composition: this object
     * is what the navigator can reach, while the snackbar host is drawn by the home page
     */
    fun shouldHint(): Boolean {
        val now = System.currentTimeMillis()
        if (now - hintedAt < WINDOW_MS) return false
        hintedAt = now
        return true
    }
}
