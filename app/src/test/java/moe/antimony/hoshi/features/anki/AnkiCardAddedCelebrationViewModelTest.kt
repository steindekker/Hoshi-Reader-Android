package moe.antimony.hoshi.features.anki

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnkiCardAddedCelebrationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addedCardsCelebrateWithTheTermAndACount() = runTest(dispatcher) {
        val events = AnkiCardAddedEvents()
        val viewModel = AnkiCardAddedCelebrationViewModel(events)
        runCurrent()

        events.publish(AnkiCardAdded(" 猫 "))
        runCurrent()
        val first = viewModel.celebration.value!!
        assertEquals("猫", first.term)
        assertEquals(1, first.count)

        events.publish(AnkiCardAdded("猫"))
        runCurrent()
        val second = viewModel.celebration.value!!
        assertEquals(2, second.count)
        assertTrue(second.id != first.id)

        // A stale finish from the replaced celebration does not hide the new one.
        viewModel.consume(first.id)
        assertEquals(second, viewModel.celebration.value)
        viewModel.consume(second.id)
        assertNull(viewModel.celebration.value)
    }
}
