package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfSnackbarPresentationTest {
    @Test
    fun successStateIsConsumedOnlyAfterSnackbarFinishes() = runBlocking {
        val snackbarStarted = CompletableDeferred<Unit>()
        val finishSnackbar = CompletableDeferred<Unit>()
        var consumed = false

        val presentation = launch {
            presentShelfCreationMoveSuccess(
                message = "Moved",
                showSnackbar = {
                    snackbarStarted.complete(Unit)
                    finishSnackbar.await()
                },
                consumeSuccess = { consumed = true },
            )
        }

        snackbarStarted.await()
        assertFalse(consumed)

        finishSnackbar.complete(Unit)
        presentation.join()
        assertTrue(consumed)
    }
}
