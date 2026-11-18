package app.shieldvpn.core

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JNI wrapper. The native handle is only touched while holding the lock:
 * shouldBlock() takes the read lock (many concurrent queries), close() takes
 * the write lock, so destroy can never overlap a native call and a closed
 * engine never reaches native code.
 */
class Engine private constructor(handle: Long) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private var handle: Long = handle // guarded by lock; 0 = closed

    /** Returns false (allow) if the engine is closed. */
    fun shouldBlock(host: String): Boolean = lock.read {
        val h = handle
        if (h == 0L) false else nativeShouldBlock(h, host)
    }

    override fun close() {
        lock.write {
            val h = handle
            handle = 0L
            if (h != 0L) nativeDestroy(h)
        }
    }

    companion object {
        init { System.loadLibrary("shieldcore") }

        /** Returns null if the native side failed to build an engine. */
        fun create(rules: ByteArray): Engine? {
            val h = nativeCreate(rules)
            return if (h == 0L) null else Engine(h)
        }

        @JvmStatic external fun nativeCreate(rules: ByteArray): Long
        @JvmStatic external fun nativeShouldBlock(handle: Long, host: String): Boolean
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
