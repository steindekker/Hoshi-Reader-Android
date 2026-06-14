package moe.antimony.hoshi.testing

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

class CountingCoroutineDispatcher(
    threadName: String = "counting-test-dispatcher",
) : CoroutineDispatcher(), Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName)
    }
    private val count = AtomicInteger()

    val dispatchCount: Int
        get() = count.get()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        count.incrementAndGet()
        executor.execute(block)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
