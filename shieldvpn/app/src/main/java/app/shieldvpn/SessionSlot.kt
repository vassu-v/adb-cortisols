package app.shieldvpn

/**
 * Holds the current session. Taking it out (swap with null) when stopping means a late
 * "session ended" callback from an old session can tell it is no longer current and must
 * not tear down its successor.
 */
class SessionSlot<T : Any> {
    @Volatile private var cur: T? = null
    @Synchronized fun swap(next: T?): T? { val old = cur; cur = next; return old }
    fun isCurrent(x: T): Boolean = cur === x
}
