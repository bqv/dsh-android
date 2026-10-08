package uk.xa0.dsh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.EffortOption
import uk.xa0.dsh.ModelOption
import uk.xa0.dsh.model.ModelChoice
import uk.xa0.dsh.model.ModelChoiceSource
import uk.xa0.dsh.model.ModelPendency
import uk.xa0.dsh.model.ModelRef

/**
 * The model trigger's copy.
 *
 * The chip is the only place the app states which model a session will run, and the
 * complaint was precisely that its statement was untrue. These cases pin the three
 * ways it stays true: it names the host's route, it marks a route that is not in
 * force yet, and it still names a route that has left the catalog instead of falling
 * back to whatever it last matched.
 */
class ModelTriggerStateTest {

    private val flash = ModelRef("deepseek-official", "deepseek-flash", "high")
    private val option = ModelOption(
        provider = "deepseek-official",
        providerName = "DeepSeek",
        model = "deepseek-flash",
        name = "DeepSeek-V41-Flash",
        efforts = listOf(EffortOption("off", "Off"), EffortOption("high", "High")),
        defaultEffort = "high",
    )

    private fun choice(
        ref: ModelRef? = flash,
        source: ModelChoiceSource = ModelChoiceSource.SESSION_NEXT,
        pendency: ModelPendency = ModelPendency.NONE,
        lastUsed: ModelRef? = null,
        unavailable: Boolean = false,
    ) = ModelChoice(ref, source, pendency, lastUsed, unavailable)

    @Test
    fun `an in-force selection is a bare label with no marker`() {
        val state = modelTriggerState(choice(), option, "high")

        assertEquals("DeepSeek-Flash · High", state?.label)
        assertNull(state?.marker)
    }

    @Test
    fun `a switch no turn has run yet says so beside the label`() {
        val was = ModelRef("deepseek-official", "deepseek-v4-pro", "high")
        val state = modelTriggerState(
            choice(pendency = ModelPendency.NEXT_TURN, lastUsed = was),
            option,
            "high",
        )

        assertEquals("next turn", state?.marker)
        // The sentence has to name what is *running*, which is the whole complaint.
        assertTrue(state!!.description!!.contains("deepseek-official/deepseek-v4-pro"))
    }

    @Test
    fun `a hero pick is marked as applying when the session is created`() {
        val state = modelTriggerState(
            choice(source = ModelChoiceSource.PENDING_PICK, pendency = ModelPendency.ON_CREATE),
            option,
            "high",
        )

        assertEquals("when created", state?.marker)
    }

    @Test
    fun `a pick still out with the host is marked as in flight`() {
        val state = modelTriggerState(
            choice(source = ModelChoiceSource.PENDING_PICK, pendency = ModelPendency.IN_FLIGHT),
            option,
            "high",
        )

        assertEquals("applying\u2026", state?.marker)
    }

    @Test
    fun `a route that left the catalog names itself and is flagged`() {
        // No catalog entry to take a name from: the host's own id is the honest
        // stand-in. Falling back to the previously matched entry would put a name on
        // screen for a different model.
        val state = modelTriggerState(choice(unavailable = true), option = null, effort = "high")

        assertEquals("deepseek-official/deepseek-flash · high", state?.label)
        assertEquals("unavailable", state?.marker)
        assertTrue(state!!.alert)
        assertTrue(state.description!!.contains("not available"))
    }

    @Test
    fun `the host default is said out loud rather than passed off as a session choice`() {
        val state = modelTriggerState(choice(source = ModelChoiceSource.HOST_DEFAULT), option, "high")

        assertNull(state?.marker)
        assertTrue(state!!.description!!.contains("default"))
    }

    @Test
    fun `nothing to advertise yields no trigger, so the pill is not drawn empty`() {
        assertNull(modelTriggerState(ModelChoice(), option = null, effort = null))
    }

    @Test
    fun `the sheet says the global default stands when the session has no model of its own`() {
        val status = modelSheetStatus(
            choice(source = ModelChoiceSource.HOST_DEFAULT),
            option,
            "high",
            sessionOpen = true,
        )

        assertTrue(status!!.contains("no model of its own"))
    }

    @Test
    fun `the sheet distinguishes pending from in force`() {
        val was = ModelRef("deepseek-official", "deepseek-v4-pro", "high")
        val status = modelSheetStatus(
            choice(pendency = ModelPendency.NEXT_TURN, lastUsed = was),
            option,
            "high",
            sessionOpen = true,
        )

        assertTrue(status!!.startsWith("Pending:"))
        assertTrue(status.contains("deepseek-official/deepseek-v4-pro"))
        // An ordinary selection says nothing: the check mark on the row is enough.
        assertNull(modelSheetStatus(choice(), option, "high", sessionOpen = true))
    }

    @Test
    fun `the sheet tells the reader what to do about an unavailable route`() {
        val status = modelSheetStatus(choice(unavailable = true), option, "high", sessionOpen = true)

        assertTrue(status!!.contains("Select another model"))
    }

    @Test
    fun `short names elide the version, not the family`() {
        assertEquals("DeepSeek-Flash", shortModelName("DeepSeek-V41-Flash"))
        // First and last segment: the middle is the version and the quantisation.
        assertEquals("Spark-768k", shortModelName("Spark-X2.5-4B-Q4_K_M-768k"))
        assertEquals("Qwen3.5", shortModelName("Qwen3.5"))
        assertNull(shortModelName(null))
    }
}
