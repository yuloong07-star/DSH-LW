package io.github.yuloong07star.luwi.ui

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

    /** A press only counts as the second one inside this window, in milliseconds */
    private const val WINDOW_MS = 2_500L

    /** When the last press happened, or 0 when there has not been one yet */
    private var lastPress = 0L

    /**
     * When the message that is up right now was put up
     *
     * This is how "is it still on screen" is answered: `SnackbarHostState` keeps its entries to
     * itself (the accessor is internal to the library), while the two numbers - when it went up and
     * how long it stays - are ours
     */
    private var hintedAt = 0L

    /**
     * One press of the back button
     *
     * Answers true when this press means "leave", which is either the second press inside the window
     * or a press made while the message is still readable. A first press answers false, and the
     * caller puts the message up
     */
    fun press(): Boolean {
        val now = System.currentTimeMillis()
        if (lastPress != 0L && now - lastPress < WINDOW_MS) {
            lastPress = 0L
            hintedAt = 0L
            return true
        }
        if (now - hintedAt < VISIBLE_MS) {
            // The message is still up, so this press is the "second one" even if the first was a
            // while ago: pressing back twice should not put two messages up and then leave
            lastPress = 0L
            hintedAt = 0L
            return true
        }
        lastPress = now
        return false
    }

    /**
     * 这一下要不要弹提示
     *
     * 与 [press] 分开是因为弹的人需要一个 composition (提示条挂在主页上), 而这个对象是导航那一层
     * 够得到的东西
     */
    fun showHint(): Boolean {
        val now = System.currentTimeMillis()
        if (now - hintedAt < VISIBLE_MS) return false
        hintedAt = now
        return true
    }

    /**
     * Where the message goes
     *
     * Held here rather than remembered in the composition because the back callback is a plain
     * lambda: showing happens in the home page, asking for it happens outside a composition
     */
    val host = SnackbarHostState()
}
