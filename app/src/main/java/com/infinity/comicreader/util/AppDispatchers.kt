package com.infinity.comicreader.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The single place where the app binds to platform coroutine dispatchers.
 *
 * Everything else depends on these injectable [CoroutineDispatcher] values rather
 * than hard-coding `Dispatchers.IO` / `Dispatchers.Main` at the call site, which
 * keeps the coroutine code testable (dispatchers can be swapped for a test
 * dispatcher) and satisfies Sonar rule kotlin:S6310 ("Dispatchers should be
 * injectable").
 */
object AppDispatchers {
    /** For disk, archive and bitmap work that must stay off the main thread. */
    val io: CoroutineDispatcher = Dispatchers.IO

    /** For touching views and adapters. */
    val main: CoroutineDispatcher = Dispatchers.Main.immediate
}
