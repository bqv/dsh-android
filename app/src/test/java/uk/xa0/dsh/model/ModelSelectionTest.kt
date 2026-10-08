package uk.xa0.dsh.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model selector's rules, as the state machine the view model drives.
 *
 * Every case here is one the app has actually got wrong, or one the host was probed
 * for on 0.2.0-rc.2:
 *
 *  * a session switched to a local model still drew the chip from the catalog's
 *    global `default` (`modelSelection` is the session's, `default` is not);
 *  * the chip was set from the *request* and never reconciled with the host's answer;
 *  * it was refreshed only at connect, so it carried across session switches;
 *  * and the new-session screen's pick went nowhere at all, because a pick with no
 *    session id was dropped after repainting the chip optimistically.
 */
class ModelSelectionTest {

    private val flash = ModelRef("deepseek-official", "deepseek-flash", "high")
    private val local = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-128k", "high")
    private val catalogDefault = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-128k", "high")

    /** Everything named in this test is listed, which is the ordinary case. */
    private val known: (ModelRef) -> Boolean = { true }

    @Test
    fun `a session's own selection outranks the global default`() {
        // Probed: a session holding deepseek-flash while `catalog.default` is a local
        // model. The chip used to draw the local default here.
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = flash, next = flash))
            .withCatalogDefault(catalogDefault)

        val choice = state.choice(sessionOpen = true, isKnown = known)

        assertEquals(flash, choice.ref)
        assertEquals(ModelChoiceSource.SESSION_NEXT, choice.source)
        assertEquals(ModelPendency.NONE, choice.pendency)
    }

    @Test
    fun `a selection the last turn has not run yet is pending, and names what ran`() {
        // Probed live: after `selectModel` on a session whose last turn ran v4-pro,
        // the host answers `lastUsed: v4-pro, next: deepseek-flash`. The next turn
        // will use the new one; nothing has run it.
        val was = ModelRef("deepseek-official", "deepseek-v4-pro", "high")
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = was, next = flash))

        val choice = state.choice(sessionOpen = true, isKnown = known)

        assertEquals(flash, choice.ref)
        assertEquals(ModelPendency.NEXT_TURN, choice.pendency)
        assertEquals(was, choice.lastUsed)
    }

    @Test
    fun `a first selection on a session that has never run is not a pending change`() {
        // Probed: a freshly created session projects `{lastUsed: null, next: null}`;
        // after a pick, `{lastUsed: null, next: <pick>}`. Nothing contradicts the
        // selection, so calling it "pending" would invent a disagreement.
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = null, next = flash))

        val choice = state.choice(sessionOpen = true, isKnown = known)

        assertEquals(ModelPendency.NONE, choice.pendency)
        assertEquals(flash, choice.ref)
    }

    @Test
    fun `a session with no selection of its own advertises the host default`() {
        // Probed: `{lastUsed: null, next: null}` on a blank session. The next request
        // resolves the host's global default, so that is what the chip must say.
        val state = ModelSelectionState(host = HostModelSelection(null, null))
            .withCatalogDefault(catalogDefault)

        val choice = state.choice(sessionOpen = true, isKnown = known)

        assertEquals(catalogDefault, choice.ref)
        assertEquals(ModelChoiceSource.HOST_DEFAULT, choice.source)
    }

    @Test
    fun `a missing projection is treated as no selection, not as the previous session's`() {
        // The old chip was only refreshed when the projection landed, and returned
        // early when `next` was null — so switching to a blank session kept the
        // previous session's model on screen indefinitely.
        val state = ModelSelectionState(host = null).withCatalogDefault(catalogDefault)

        val choice = state.choice(sessionOpen = true, isKnown = known)

        assertEquals(catalogDefault, choice.ref)
        assertEquals(ModelChoiceSource.HOST_DEFAULT, choice.source)
    }

    @Test
    fun `a pick on the new-session screen is pending on create, not in force`() {
        val state = ModelSelectionState().withCatalogDefault(catalogDefault).withPendingPick(flash)

        val choice = state.choice(sessionOpen = false, isKnown = known)

        assertEquals(flash, choice.ref)
        assertEquals(ModelChoiceSource.PENDING_PICK, choice.source)
        assertEquals(ModelPendency.ON_CREATE, choice.pendency)
    }

    @Test
    fun `the new-session screen advertises the host default until something is picked`() {
        val state = ModelSelectionState().withCatalogDefault(catalogDefault)

        val choice = state.choice(sessionOpen = false, isKnown = known)

        assertEquals(catalogDefault, choice.ref)
        assertEquals(ModelChoiceSource.HOST_DEFAULT, choice.source)
        assertEquals(ModelPendency.NONE, choice.pendency)
    }

    @Test
    fun `a pick in flight is shown as in flight and outranks the host's stale next`() {
        // The session still holds the old route while the call is out; claiming the
        // old route is what the chip would have done before, and claiming the new one
        // outright is what it did wrong for a pick the host then refused.
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = flash, next = flash))
            .withInFlight(local)

        val choice = state.choice(sessionOpen = true, isKnown = known)

        assertEquals(local, choice.ref)
        assertEquals(ModelPendency.IN_FLIGHT, choice.pendency)
    }

    @Test
    fun `a route the host no longer lists is advertised but marked unavailable`() {
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = local, next = local))
        val catalog = setOf(flash)

        val choice = state.choice(sessionOpen = true, isKnown = { it in catalog })

        assertEquals(local, choice.ref)
        assertTrue(choice.unavailable)
    }

    @Test
    fun `a catalog that has not answered condemns nothing`() {
        // A failed or still-in-flight `session/modelCatalog` leaves the model list
        // empty. Reading that as "this route is gone" would put a fault on a model the
        // host is perfectly willing to run — a lie in the opposite direction.
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = local, next = local))

        val choice = state.choice(sessionOpen = true, isKnown = null)

        assertEquals(local, choice.ref)
        assertFalse(choice.unavailable)
    }

    @Test
    fun `nothing to advertise is its own state`() {
        val choice = ModelSelectionState().choice(sessionOpen = true, isKnown = known)

        assertNull(choice.ref)
        assertEquals(ModelChoiceSource.NONE, choice.source)
    }

    @Test
    fun `the host's answer is adopted, not the request`() {
        // Probed: selecting a route with no effort is answered with the route's
        // `defaultEffort` filled in — the host resolves, it does not echo.
        val asked = ModelRef("deepseek-official", "deepseek-v4-pro", null)
        val answered = ModelRef("deepseek-official", "deepseek-v4-pro", "high")
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = flash, next = flash))
            .withInFlight(asked)

        val settled = state.withAnswer(answered)

        assertEquals(answered, settled.host?.next)
        assertEquals(flash, settled.host?.lastUsed)
        assertNull(settled.inFlight)
    }

    @Test
    fun `a refusal drops the pick and leaves the host's selection alone`() {
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = flash, next = flash))
            .withInFlight(local)

        val refused = state.withRefusal()
        val choice = refused.choice(sessionOpen = true, isKnown = known)

        assertEquals(flash, choice.ref)
        assertEquals(ModelPendency.NONE, choice.pendency)
        assertFalse(choice.unavailable)
    }

    @Test
    fun `opening a session drops the previous session's selection and the hero pick`() {
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = flash, next = flash))
            .withCatalogDefault(catalogDefault)
            .withPendingPick(local)
            .withInFlight(local)

        val next = state.onSessionOpened()

        assertNull(next.host)
        assertNull(next.pendingPick)
        assertNull(next.inFlight)
        // Host-wide, so it survives: it is what a session with no selection runs.
        assertEquals(catalogDefault, next.catalogDefault)
        // And until the new session's own projection lands, that is the honest chip.
        assertEquals(catalogDefault, next.choice(sessionOpen = true, isKnown = known).ref)
    }

    @Test
    fun `leaving for the new-session seat keeps the pick it was holding`() {
        // Re-recording the seat's target (a different Workspace) is not a reason to
        // discard a model chosen for the session that seat will create.
        val state = ModelSelectionState(host = HostModelSelection(lastUsed = flash, next = flash))
            .withPendingPick(local)
            .withInFlight(local)

        val left = state.onSessionLeft()

        assertNull(left.host)
        assertNull(left.inFlight)
        assertEquals(local, left.pendingPick)
    }

    @Test
    fun `parsing reads both halves and tolerates an absent effort`() {
        val selection = parseHostModelSelection(
            JSONObject(
                """
                {"lastUsed":{"provider":"deepseek-official","model":"deepseek-flash","reasoningEffort":"low"},
                 "next":{"provider":"dsh-local","model":"Qwen3.5-35B-A3B-UD-IQ4_XS"}}
                """.trimIndent(),
            ),
        )

        assertEquals(
            ModelRef("deepseek-official", "deepseek-flash", "low"),
            selection?.lastUsed,
        )
        // Null effort, not "off": the route's own default applies.
        assertEquals("dsh-local", selection?.next?.provider)
        assertNull(selection?.next?.reasoningEffort)
    }

    @Test
    fun `parsing a blank session's projection yields two nulls, not a missing value`() {
        val selection = parseHostModelSelection(JSONObject("""{"lastUsed":null,"next":null}"""))

        assertEquals(HostModelSelection(lastUsed = null, next = null), selection)
    }

    @Test
    fun `a reference without a provider or a model is not a route`() {
        assertNull(parseModelRef(JSONObject("""{"model":"deepseek-flash"}""")))
        assertNull(parseModelRef(JSONObject("""{"provider":"deepseek-official"}""")))
        assertNull(parseModelRef(JSONObject("""{"provider":"deepseek-official","model":null}""")))
        assertNull(parseModelRef(null))
        assertEquals(
            "deepseek-official/deepseek-flash",
            parseModelRef(JSONObject("""{"provider":"deepseek-official","model":"deepseek-flash"}"""))?.id,
        )
    }
}
