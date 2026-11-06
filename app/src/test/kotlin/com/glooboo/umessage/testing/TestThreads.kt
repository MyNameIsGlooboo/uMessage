package com.glooboo.umessage.testing

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Runs [block] on a fresh background thread and returns its result, rethrowing whatever it threw.
 *
 * Robolectric runs tests on the main thread, and Room refuses queries there unless the database was built with
 * allowMainThreadQueries(). Use this when a test must go through the app's own builder (for example
 * MessagesDatabase.build), which does not allow them.
 */
fun <T> offMainThread(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor()
    try {
        return executor.submit(block).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } finally {
        executor.shutdown()
    }
}
