package uk.xa0.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.model.HostModelSelection
import uk.xa0.dsh.model.ModelChoiceSource
import uk.xa0.dsh.model.ModelPendency
import uk.xa0.dsh.model.ModelRef
import uk.xa0.dsh.model.ModelSelectionState

/**
 * The chip the UI reads, derived from the state it is supposed to describe.
 *
 * These assertions are the structural half of "one writer": [UiState.modelChoice],
 * [UiState.selectedModel] and [UiState.selectedEffort] have no backing field, so the
 * only way to change them is to change [UiState.modelSelection] (or the catalog, or
 * the open session) and let them re-derive. A test that the values follow their inputs
 * is therefore also a test that no cached copy can lag behind.
 *
 * The view model itself needs an `Application` and a host, so it is not unit-testable
 * here; what *is* testable is the state those rules are applied to.
 */
class UiStateModelChoiceTest {

    private val flashRef = ModelRef("deepseek-official", "deepseek-flash", "high")
    private val localRef = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-128k", "high")

    private val flash = ModelOption(
        provider = "deepseek-official",
        providerName = "DeepSeek",
        model = "deepseek-flash",
        name = "DeepSeek-V41-Flash",
        efforts = listOf(EffortOption("off", "Off"), EffortOption("high", "High")),
        defaultEffort = "high",
    )
    private val local = ModelOption(
        provider = "dsh-local",
        providerName = "Local Model (singleton)",
        model = "Qwen3.5-35B-A3B-UD-IQ4_XS-128k",
        name = "Qwen3.5-35B-A3B",
        efforts = listOf(EffortOption("off", "Off"), EffortOption("high", "High")),
        defaultEffort = "high",
    )
    private val catalog = listOf(local, flash)

    private fun state(
        sessionId: String?,
        selection: ModelSelectionState,
    ) = UiState(currentSessionId = sessionId, models = catalog, modelSelection = selection)

    @Test
    fun `the chip names the session's next, never the catalog's global default`() {
        // The original bug: a session switched to a local model still drew the chip
        // from `catalog.default`. `default` is not this session's selection.
        val ui = state(
            "s1",
            ModelSelectionState(host = HostModelSelection(lastUsed = localRef, next = localRef))
                .withCatalogDefault(flashRef),
        )

        assertEquals("dsh-local", ui.selectedModel?.provider)
        assertEquals(ModelChoiceSource.SESSION_NEXT, ui.modelChoice.source)
    }

    @Test
    fun `a switch no turn has run yet is exposed as pending, with what ran`() {
        val ui = state(
            "s1",
            ModelSelectionState(host = HostModelSelection(lastUsed = flashRef, next = localRef)),
        )

        assertEquals("dsh-local", ui.selectedModel?.provider)
        assertEquals(ModelPendency.NEXT_TURN, ui.modelChoice.pendency)
        assertEquals(flashRef, ui.modelChoice.lastUsed)
    }

    @Test
    fun `a blank session falls back to the host's global default`() {
        // A session with no selection of its own runs the host default, so that is the
        // honest thing to advertise — and it is a *different* claim from a selection,
        // which is why `source` distinguishes them.
        val ui = state(
            "s1",
            ModelSelectionState(host = HostModelSelection(lastUsed = null, next = null))
                .withCatalogDefault(flashRef),
        )

        assertEquals("deepseek-official", ui.selectedModel?.provider)
        assertEquals(ModelChoiceSource.HOST_DEFAULT, ui.modelChoice.source)
        assertEquals(ModelPendency.NONE, ui.modelChoice.pendency)
    }

    @Test
    fun `a new-session pick is shown as pending on create, not as in force`() {
        val ui = state(
            null,
            ModelSelectionState().withCatalogDefault(localRef).withPendingPick(flashRef),
        )

        assertEquals("deepseek-official", ui.selectedModel?.provider)
        assertEquals(ModelPendency.ON_CREATE, ui.modelChoice.pendency)
    }

    @Test
    fun `a pick still out with the host outranks what the session currently holds`() {
        val ui = state(
            "s1",
            ModelSelectionState(host = HostModelSelection(lastUsed = flashRef, next = flashRef))
                .withInFlight(localRef),
        )

        assertEquals("dsh-local", ui.selectedModel?.provider)
        assertEquals(ModelPendency.IN_FLIGHT, ui.modelChoice.pendency)
    }

    @Test
    fun `a route the catalog has dropped is named but not offered`() {
        // The route is still what the session holds, so the chip names it from the
        // host's identifier; there is no catalog entry, so there is no entry for the
        // picker to mark as selected either.
        val dropped = ModelRef("dsh-local", "gone-model", "high")
        val ui = state("s1", ModelSelectionState(host = HostModelSelection(lastUsed = dropped, next = dropped)))

        assertNull(ui.selectedModel)
        assertEquals(dropped, ui.modelChoice.ref)
        assertTrue(ui.modelChoice.unavailable)
    }

    @Test
    fun `derived values follow their inputs, so no cached copy can lag`() {
        // `copy` on the state is the only way to move the chip. Swapping the session's
        // projection alone has to move it, with no separate publish step to forget.
        val before = state("s1", ModelSelectionState(host = HostModelSelection(null, null)).withCatalogDefault(flashRef))
        assertEquals("deepseek-official", before.selectedModel?.provider)

        val after = before.copy(
            modelSelection = before.modelSelection.withHostSelection(HostModelSelection(null, localRef)),
        )

        assertEquals("dsh-local", after.selectedModel?.provider)
        // The effort is the route's id, which is what the sheet's chips compare to.
        assertEquals("high", after.selectedEffort)
        // …and the original value is untouched, because it is derived and not shared.
        assertEquals("deepseek-official", before.selectedModel?.provider)
    }

    @Test
    fun `an unloaded catalog condemns nothing`() {
        val ui = UiState(
            currentSessionId = "s1",
            models = emptyList(),
            modelSelection = ModelSelectionState(host = HostModelSelection(localRef, localRef)),
        )

        assertNull(ui.selectedModel)
        assertTrue("an empty catalog is not evidence against the route", !ui.modelChoice.unavailable)
    }
}
