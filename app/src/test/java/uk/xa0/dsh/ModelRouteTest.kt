package uk.xa0.dsh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.model.ModelRef

/**
 * Whether a route in the chip can actually be run.
 *
 * Probed on host 0.2.0-rc.2: a model the catalog does not list, a provider it does
 * not list, and an effort a listed route does not advertise are all answered with the
 * same hard `session/model-unavailable` and mutate nothing. So "runnable" has to mean
 * the route *and* its effort, not just the route.
 */
class ModelRouteTest {

    private val flash = ModelOption(
        provider = "deepseek-official",
        providerName = "DeepSeek",
        model = "deepseek-flash",
        name = "DeepSeek-V41-Flash",
        efforts = listOf(EffortOption("off", "Off"), EffortOption("high", "High")),
        defaultEffort = "high",
    )
    private val vision = ModelOption(
        provider = "dsh-local",
        providerName = "Local Model (singleton)",
        model = "Qwen3VL-8B-Instruct-Q4_K_M",
        name = "Qwen3-VL-8B",
        // A route with no reasoning levels accepts no effort at all.
        efforts = emptyList(),
        defaultEffort = null,
    )
    private val catalog = listOf(flash, vision)

    @Test
    fun `a listed route with a listed effort runs`() {
        assertTrue(modelRouteIsRunnable(catalog, ModelRef("deepseek-official", "deepseek-flash", "high")))
        assertTrue(modelRouteIsRunnable(catalog, ModelRef("deepseek-official", "deepseek-flash", "off")))
    }

    @Test
    fun `a route with no effort named runs, because the host applies the default`() {
        // Probed: omitting `reasoningEffort` is answered with the route's
        // `defaultEffort` filled in — the host resolves it, it does not refuse it.
        assertTrue(modelRouteIsRunnable(catalog, ModelRef("deepseek-official", "deepseek-flash", null)))
        assertTrue(modelRouteIsRunnable(catalog, ModelRef("dsh-local", "Qwen3VL-8B-Instruct-Q4_K_M", null)))
    }

    @Test
    fun `an effort the route does not advertise is not runnable`() {
        // Probed: "deepseek-flash does not support reasoning effort \"medium\"" is a
        // `session/model-unavailable` refusal, so the chip must not claim otherwise.
        assertFalse(modelRouteIsRunnable(catalog, ModelRef("deepseek-official", "deepseek-flash", "medium")))
        assertFalse(modelRouteIsRunnable(catalog, ModelRef("dsh-local", "Qwen3VL-8B-Instruct-Q4_K_M", "high")))
    }

    @Test
    fun `an unlisted model or provider is not runnable`() {
        assertFalse(modelRouteIsRunnable(catalog, ModelRef("deepseek-official", "deepseek-pro", "high")))
        assertFalse(modelRouteIsRunnable(catalog, ModelRef("free-groq", "deepseek-flash", "high")))
        assertFalse(modelRouteIsRunnable(emptyList(), ModelRef("deepseek-official", "deepseek-flash", "high")))
    }
}
