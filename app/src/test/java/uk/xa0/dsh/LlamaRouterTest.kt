package uk.xa0.dsh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.model.HostModelSelection
import uk.xa0.dsh.model.RouterProbeRecord
import uk.xa0.dsh.model.LocalProvider
import uk.xa0.dsh.model.RouterProbeResult
import uk.xa0.dsh.model.RouterTarget
import uk.xa0.dsh.model.ModelChoice
import uk.xa0.dsh.model.ModelChoiceSource
import uk.xa0.dsh.model.ModelRef
import uk.xa0.dsh.model.ModelSelectionState
import uk.xa0.dsh.model.ROUTER_STATUS_METHOD
import uk.xa0.dsh.model.RouterCallFailure
import uk.xa0.dsh.model.RouterLoad
import uk.xa0.dsh.model.RouterModelStatus
import uk.xa0.dsh.model.RouterSlot
import uk.xa0.dsh.model.RouterStatusOutcome
import uk.xa0.dsh.model.classifyRouterStatus
import uk.xa0.dsh.model.discoverModelsArgs
import uk.xa0.dsh.model.endpointHost
import uk.xa0.dsh.model.isLoopbackEndpoint
import uk.xa0.dsh.model.isMethodAbsent
import uk.xa0.dsh.model.parseRouterStatus
import uk.xa0.dsh.model.routerFailureText
import uk.xa0.dsh.model.routerStatusArgs
import uk.xa0.dsh.model.routerTargetOf
import uk.xa0.dsh.model.parseLocalProviders

/**
 * The router tab's gate, its one live fact, and the client half of the call that
 * would supply the readout it still lacks.
 *
 * The fixture below is the live host's `settings/describe` shape, trimmed: the
 * `llm-pi-ai` namespace's `providers` map with the two loopback profiles
 * (`dsh-local` → `127.0.0.1:55555`, `dsh-local-aux` → `127.0.0.1:55556`), one cloud
 * profile, and a profile that declares no endpoint. Field names, the
 * `input` / `defaultInput` split and the `headers.authorization` shape are all as
 * probed on 0.2.0-rc.2.
 *
 * The reachability half is tested for its *shape* here; its live behaviour was
 * probed by hand and is recorded in `model/LlamaRouter.kt`: a dead port answers
 * `llm/model-discovery-rejected` / `could not reach <url>`, and the running router
 * answers the model ids it advertises.
 *
 * The load-and-prefill half is tested against a fixture taken from the live router
 * (measured on `127.0.0.1:55555`, 2026-10-10). Its *transport* does not exist yet
 * — `localRouter/status` is the method specified in `docs/HANDOFF.md`, and the host
 * answers `not found` today — so what is tested here is the client's own half:
 * reading a report, classifying an absent method against a real failure, and the
 * distinction between "not reported" and "reported as nothing".
 */
class LlamaRouterTest {

    private val llmPiAi = """
    {
      "ns": "llm-pi-ai",
      "applies": "live",
      "revision": 3,
      "value": {
        "providers": {
          "dsh-local": {
            "displayName": "Local Model (singleton)",
            "api": "openai-completions",
            "baseURL": "http://127.0.0.1:55555/v1",
            "models": [
              {
                "description": "Single-slot 240k window version.",
                "id": "Qwen3.5-35B-A3B-UD-IQ4_XS-240k",
                "name": "Qwen3.5-35B-A3B - 240k single slot",
                "contextWindow": 245760,
                "maxTokens": 16384,
                "input": ["text", "image"]
              },
              {
                "id": "Qwen3.8-27B-UD-Q4_K_M",
                "name": "Qwen3.8-27B - general agent, 128k",
                "contextWindow": 131072,
                "maxTokens": 16384,
                "input": []
              }
            ],
            "defaultContextWindow": 262144,
            "defaultMaxTokens": 32768,
            "defaultInput": ["text"],
            "headers": {"authorization": "Bearer dsh-local-llm"}
          },
          "dsh-local-aux": {
            "displayName": "Local Auxiliary",
            "api": "openai-completions",
            "baseURL": "http://127.0.0.1:55556/v1",
            "models": [
              {"id": "Qwen2.5-0.5B-Instruct-Q8_0", "name": "Qwen2.5 0.5B", "contextWindow": 32768, "maxTokens": 8192, "input": []}
            ],
            "defaultInput": ["text"],
            "headers": {"authorization": "Bearer dsh-local-llm"}
          },
          "free-groq": {
            "displayName": "Groq (free tier)",
            "api": "openai-completions",
            "baseURL": "https://api.groq.com/openai/v1",
            "models": [{"id": "openai/gpt-oss-120b", "name": "GPT-OSS-120B (Groq free, 128k ctx)"}]
          },
          "half-declared": {
            "displayName": "Half declared",
            "models": []
          }
        }
      }
    }
    """.trimIndent()

    /** A `settings/describe` answer carrying [namespaces], plus one that declares nothing. */
    private fun describe(vararg namespaces: String): JSONObject {
        val array = namespaces.joinToString(",") { it }
        return JSONObject(
            """{"writable":true,"hasDocument":true,"namespaces":[$array,{"ns":"permission","value":{}}]}""",
        )
    }

    private val providers = parseLocalProviders(describe(llmPiAi))

    // ------------------------------------------------------------------ parsing

    @Test
    fun `the host's own profiles parse, endpoints and models with them`() {
        assertEquals(setOf("dsh-local", "dsh-local-aux", "free-groq"), providers.keys)

        val local = providers.getValue("dsh-local")
        assertEquals("Local Model (singleton)", local.displayName)
        assertEquals("openai-completions", local.api)
        assertEquals("http://127.0.0.1:55555/v1", local.baseURL)
        // The namespace the profile was declared in rides along: `llm/discoverModels`
        // needs it back verbatim, so it must not be a constant in the probe.
        assertEquals("llm-pi-ai", local.settingsNs)
        assertEquals(2, local.models.size)
        assertEquals(262144, local.defaultContextWindow)
        assertEquals(listOf("text"), local.defaultInput)
        assertEquals(listOf("authorization"), local.headerNames)

        val rung = local.models.getValue("Qwen3.5-35B-A3B-UD-IQ4_XS-240k")
        assertEquals(245760, rung.contextWindow)
        assertEquals(16384, rung.maxTokens)
        assertEquals(listOf("text", "image"), rung.input)
        assertEquals("Qwen3.5-35B-A3B - 240k single slot", rung.name)
    }

    @Test
    fun `a profile with no endpoint has nothing to describe, so it is not a provider`() {
        // Not "not local" — absent. The tab may only speak about a server it can
        // name, and a profile with no base URL names none.
        assertFalse(providers.containsKey("half-declared"))
        assertFalse(providers.containsKey("permission"))
    }

    @Test
    fun `a model's empty input list means the provider default, not no modalities`() {
        // The live document does exactly this: Qwen3.8-27B declares `"input": []`
        // and the provider declares `["text"]`.
        val target = targetFor(ModelRef("dsh-local", "Qwen3.8-27B-UD-Q4_K_M", "high"))!!
        assertEquals(listOf("text"), target.input)
        assertEquals(131072, target.contextWindow)

        val sighted = targetFor(ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high"))!!
        assertEquals(listOf("text", "image"), sighted.input)
    }

    @Test
    fun `a model the profile does not list falls back to the provider's declared defaults`() {
        val target = targetFor(ModelRef("dsh-local", "a-rung-the-routers-advertise-but-the-profile-does-not", null))!!
        assertNull(target.model)
        assertEquals(262144, target.contextWindow)
        assertEquals(32768, target.maxTokens)
        assertEquals(listOf("text"), target.input)
    }

    // ---------------------------------------------------------------- the gate

    @Test
    fun `a loopback endpoint is local, and nothing else is`() {
        val local = listOf(
            "http://127.0.0.1:55555/v1",
            "http://127.0.0.1",
            "http://127.0.0.9:8080/v1",
            "http://localhost:55555/v1",
            "http://[::1]:8080/v1",
            "http://127.0.0.1:55555",
            // No scheme at all: the host's own `discoverModels` error text names the
            // endpoint bare, and a settings document is hand-written JSON.
            "127.0.0.1:55555",
            "[::1]:8080",
            "http://user@127.0.0.1:55555/v1",
        )
        local.forEach { assertTrue("$it should be local", isLoopbackEndpoint(it)) }

        val remote = listOf(
            "",
            "   ",
            "https://api.groq.com/openai/v1",
            "https://api.deepseek.com/anthropic",
            // The LAN addresses nginx also listens on are NOT this host's loopback.
            "http://192.168.1.110:80/v1",
            "http://10.0.0.5:55555/v1",
            "http://127.0.0.1x:55555/v1",
            "http://2130706433/v1",
            "api.deepseek.com",
        )
        remote.forEach { assertFalse("$it should not be local", isLoopbackEndpoint(it)) }
    }

    @Test
    fun `the host of an endpoint is read without port, brackets or credentials`() {
        assertEquals("127.0.0.1", endpointHost("http://127.0.0.1:55555/v1"))
        assertEquals("::1", endpointHost("http://[::1]:8080/v1"))
        assertEquals("api.groq.com", endpointHost("https://api.groq.com/openai/v1"))
        assertEquals("127.0.0.1", endpointHost("http://user:pass@127.0.0.1:55555/v1"))
        assertNull(endpointHost(""))
    }

    @Test
    fun `a local route is the only thing that opens the tab`() {
        val local = targetFor(ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high"))!!
        assertEquals("dsh-local", local.provider.id)
        assertEquals("http://127.0.0.1:55555/v1", local.endpoint)
        assertTrue(local.authenticated)

        val aux = targetFor(ModelRef("dsh-local-aux", "Qwen2.5-0.5B-Instruct-Q8_0", "high"))!!
        assertEquals("http://127.0.0.1:55556/v1", aux.endpoint)

        // A cloud provider is in the same document and is not a target — the rule is
        // the endpoint, not the provider's name.
        assertNull(targetFor(ModelRef("free-groq", "openai/gpt-oss-120b", null)))
        assertNull(targetFor(ModelRef("deepseek-official", "deepseek-flash", "high")))
    }

    @Test
    fun `no selection at all opens nothing`() {
        assertNull(routerTargetOf(ModelChoice(ref = null, source = ModelChoiceSource.NONE), providers))
    }

    @Test
    fun `a pending switch to a cloud model closes the tab at once`() {
        // The tab has to track the route the session will *run*, not the one the last
        // turn ran: a route the session is leaving must not keep a Server tab alive.
        val choice = ModelChoice(
            ref = ModelRef("deepseek-official", "deepseek-flash", "high"),
            source = ModelChoiceSource.SESSION_NEXT,
            lastUsed = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high"),
        )
        assertNull(routerTargetOf(choice, providers))
    }

    @Test
    fun `a pending switch to a local model opens it before the turn runs`() {
        val choice = ModelChoice(
            ref = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high"),
            source = ModelChoiceSource.SESSION_NEXT,
            lastUsed = ModelRef("deepseek-official", "deepseek-flash", "high"),
        )
        assertEquals("dsh-local", routerTargetOf(choice, providers)?.provider?.id)
    }

    @Test
    fun `a local route the catalog has dropped still names its server`() {
        // The session holds the route, and the trigger names it too; the profile is
        // what says whether there is a server behind it. Being unrunnable is not a
        // reason to stop describing the server the session is pointed at.
        val choice = ModelChoice(
            ref = ModelRef("dsh-local", "gone-from-the-catalog", "high"),
            source = ModelChoiceSource.SESSION_NEXT,
            unavailable = true,
        )
        val target = routerTargetOf(choice, providers)
        assertEquals("dsh-local", target?.provider?.id)
        assertNull(target?.model)
    }

    // --------------------------------------------------------------- the probe

    @Test
    fun `the probe asks the host to open the profile's own endpoint, credential and all`() {
        val args = discoverModelsArgs(targetFor(ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high"))!!)
        assertEquals("llm-pi-ai", args.getString("settingsNs"))
        val request = args.getJSONObject("request")
        assertEquals("http://127.0.0.1:55555/v1", request.getString("baseURL"))
        assertEquals("openai-completions", request.getString("api"))
        // The profile's `Bearer dsh-local-llm`, forwarded as the api key so the probe
        // authenticates exactly as a real request would.
        assertEquals("dsh-local-llm", request.getString("apiKey"))
    }

    @Test
    fun `the probe names no provider, because a catalogued id would be answered from the catalog`() {
        // Probed on 0.2.0-rc.2: `{"provider":"groq","baseURL":"http://127.0.0.1:55599/v1"}`
        // — a port with nothing listening — answers `ok:true` with Groq's cloud
        // catalogue, because the host checks its own shipped pi-ai catalog by
        // provider id before it touches the network. A reachability verdict built
        // from that would be confident and false, so the provider is left unnamed
        // and the call is unconditionally a live `GET <baseURL>/models`.
        val args = discoverModelsArgs(targetFor(ModelRef("dsh-local", "Qwen3.8-27B-UD-Q4_K_M", "high"))!!)
        assertFalse(args.getJSONObject("request").has("provider"))
    }

    @Test
    fun `a credential in any other scheme is not forwarded as an api key`() {
        // `apiKey` has no honest mapping for a non-bearer header, and sending the
        // wrong one would turn "the server is down" into "the server refused us".
        val provider = LocalProvider(
            id = "odd",
            settingsNs = "llm-pi-ai",
            displayName = "Odd",
            baseURL = "http://127.0.0.1:9999/v1",
            authHeader = "Token abc123",
        )
        val args = discoverModelsArgs(
            RouterTarget(provider = provider, modelId = "m"),
        )
        assertFalse(args.getJSONObject("request").has("apiKey"))
    }

    @Test
    fun `a reachable answer knows whether it advertises the session's model`() {
        val reachable = RouterProbeResult.Reachable(
            modelIds = listOf("Qwen3.5-35B-A3B-UD-IQ4_XS", "Spark-X2.5-4B-Q4_K_M"),
            atMillis = 1L,
        )
        assertTrue(reachable.advertises("Spark-X2.5-4B-Q4_K_M"))
        assertFalse(reachable.advertises("Qwen3.8-27B-UD-Q4_K_M"))
    }

    @Test
    fun `a failure keeps the host's own words, except where the host has none`() {
        // The host's message names the endpoint that failed, which no paraphrase
        // improves on.
        assertEquals(
            "could not reach http://127.0.0.1:55599/v1/models",
            routerFailureText("llm/model-discovery-rejected", "could not reach http://127.0.0.1:55599/v1/models"),
        )
        // The carrier answers an unexposed method with a bare `not found` and HTTP
        // 404 — which is not about the server at all.
        assertEquals(
            "This host does not offer the model-discovery call to this client.",
            routerFailureText("http/404", "not found"),
        )
        assertEquals(
            "This host does not offer the model-discovery call to this client.",
            routerFailureText("", "not found"),
        )
        assertEquals("The host did not answer.", routerFailureText("", "  "))
    }

    // ------------------------------------------------------------ the tab's gate

    @Test
    fun `the tab's gate tracks the session's selected route, and a stale check is not shown`() {
        val local = ModelOption(
            provider = "dsh-local",
            providerName = "Local Model (singleton)",
            model = "Qwen3.5-35B-A3B-UD-IQ4_XS-240k",
            name = "Qwen3.5-35B-A3B",
            efforts = listOf(EffortOption("off", "Off"), EffortOption("high", "High")),
            defaultEffort = "high",
        )
        val flash = ModelOption(
            provider = "deepseek-official",
            providerName = "DeepSeek",
            model = "deepseek-flash",
            name = "DeepSeek-V41-Flash",
            defaultEffort = "high",
        )
        val catalog = listOf(local, flash)

        fun state(ref: ModelRef, probe: RouterProbeRecord? = null) = UiState(
            currentSessionId = "s1",
            models = catalog,
            modelSelection = ModelSelectionState(host = HostModelSelection(lastUsed = ref, next = ref)),
            localProviders = providers,
            routerProbeRecord = probe,
        )

        val localRef = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high")

        // A cloud route: no tab at all.
        assertNull(state(ModelRef("deepseek-official", "deepseek-flash", "high")).routerTarget)
        // A local route: the tab, with the profile's endpoint.
        assertEquals("http://127.0.0.1:55555/v1", state(localRef).routerTarget?.endpoint)

        // A check made for another route must not be drawn beside this one.
        val other = RouterProbeRecord("dsh-local-aux", "Qwen2.5-0.5B-Instruct-Q8_0", RouterProbeResult.Reachable(listOf("x"), 5L))
        assertEquals(RouterProbeResult.Idle, state(localRef, other).routerProbe)
        // …nor one made for another model of the same provider.
        val otherModel = RouterProbeRecord("dsh-local", "Qwen3.8-27B-UD-Q4_K_M", RouterProbeResult.Reachable(listOf("x"), 5L))
        assertEquals(RouterProbeResult.Idle, state(localRef, otherModel).routerProbe)
        // …while the matching one is shown, timestamp and all.
        val mine = RouterProbeRecord("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", RouterProbeResult.Reachable(listOf("y"), 5L))
        assertEquals(RouterProbeResult.Reachable(listOf("y"), 5L), state(localRef, mine).routerProbe)

        // No document read yet: the tab is closed, because "not loaded" is not "local".
        val unread = UiState(
            currentSessionId = "s1",
            models = catalog,
            modelSelection = ModelSelectionState(host = HostModelSelection(localRef, localRef)),
        )
        assertNull(unread.routerTarget)
        assertEquals(RouterProbeResult.Idle, unread.routerProbe)
    }

    // ------------------------------------------- the router's own load and prefill

    @Test
    fun `the status request is the discovery request, so the two cannot name different endpoints`() {
        val target = targetFor(ModelRef("dsh-local", "Qwen3.8-27B-UD-Q4_K_M", "high"))!!
        val discovery = discoverModelsArgs(target)
        val status = routerStatusArgs(target)
        assertEquals(discovery.getJSONObject("request").toString(), status.getJSONObject("request").toString())
        assertEquals(discovery.getString("settingsNs"), status.getString("settingsNs"))
        // The same trap applies: a named provider would be answered from the host's
        // own catalogue without touching the network.
        assertFalse(status.getJSONObject("request").has("provider"))
    }

    @Test
    fun `the status method is the one the handoff specifies, and its absence is not a router verdict`() {
        assertEquals("localRouter/status", ROUTER_STATUS_METHOD)
        // The carrier's own words for an unexposed method: a fact about the host's
        // composition, and the only failure the caller may fall back from.
        assertTrue(isMethodAbsent("http/404", "not found"))
        assertTrue(isMethodAbsent("", "Not Found"))
        // A real verdict on the router is never mistaken for an absent method.
        assertFalse(isMethodAbsent("llm/model-discovery-rejected", "could not reach http://127.0.0.1:55599/v1/models"))
        assertFalse(isMethodAbsent("", ""))
    }

    @Test
    fun `a full report is read into the very numbers the router published`() {
        // The reply is the shape spec'd in docs/HANDOFF.md, and the values are the
        // ones measured on 127.0.0.1:55555 on 2026-10-10: twelve advertised models,
        // `Qwen3.8-27B-abliterated-120k` resident, and a slot mid-prefill.
        val load = parseRouterStatus(JSONObject(FULL_REPORT))!!
        assertEquals("router", load.role)
        assertEquals(1, load.maxInstances)
        assertEquals(true, load.modelsAutoload)
        assertEquals(3, load.models.size)
        assertEquals(listOf("Qwen3.8-27B-abliterated-120k"), load.loaded.map { it.id })
        assertEquals(122880, load.loaded.first().contextSize)
        assertEquals(43723, load.loaded.first().childPort)
        // A model on its way in is neither loaded nor unloaded, and the tab draws
        // that third state rather than rounding it to one of the other two.
        assertEquals(listOf("Spark-X2.5-4B-Q4_K_M"), load.loading.map { it.id })

        assertEquals("Qwen3.8-27B-abliterated-120k", load.slotsModel)
        assertEquals(122880, load.slotsContextSize)
        assertEquals(1, load.slots.size)
        val slot = load.slots.first()
        assertEquals(0, slot.id)
        assertTrue(slot.isProcessing)
        assertEquals(38893, slot.promptTokens)
        assertEquals(1032, slot.promptTokensProcessed)
        assertEquals(37639, slot.promptTokensCache)
        assertEquals(222, slot.decoded)
        assertEquals(16162, slot.remaining)
        assertEquals(38893 - 1032, slot.promptTokensRemaining)
        assertEquals(1, load.processing.size)
    }

    @Test
    fun `a partial report yields nulls and empty lists rather than failing the whole probe`() {
        // A plugin that forwards only /v1/models is still a measurement. The
        // distinction that matters: `models` absent reads as "listed nothing", which
        // is an empty list — NOT as null, which would mean "not a report at all".
        val load = parseRouterStatus(JSONObject("""{"models":[{"id":"a","status":"unloaded"}]}"""))!!
        assertEquals(1, load.models.size)
        assertNull(load.role)
        assertNull(load.maxInstances)
        assertNull(load.modelsAutoload)
        assertNull(load.slotsModel)
        assertNull(load.slotsContextSize)
        assertTrue(load.slots.isEmpty())
        assertTrue(load.loaded.isEmpty())

        val empty = parseRouterStatus(JSONObject("""{"models":[]}"""))!!
        assertTrue(empty.models.isEmpty())
    }

    @Test
    fun `a reply that is not a router report is null, so the caller falls through`() {
        // Null is the parser's "this is not a report", never "the router reported
        // nothing" — the two are different facts and the tab words them differently.
        assertNull(parseRouterStatus(null))
        assertNull(parseRouterStatus("a string"))
        assertNull(parseRouterStatus(org.json.JSONArray()))
        // An object carrying none of the spec's fields is not a report either. It
        // would otherwise parse to an all-null RouterLoad and be drawn as a router
        // that reported nothing.
        assertNull(parseRouterStatus(JSONObject("{}")))
        assertNull(parseRouterStatus(JSONObject("""{"unexpected":true}""")))
    }

    @Test
    fun `only an absent method lets the caller fall back to discovery`() {
        // Reported: the host has the plugin and answered it.
        val reported = classifyRouterStatus(JSONObject(FULL_REPORT), null)
        assertTrue(reported is RouterStatusOutcome.Reported)
        assertEquals(
            "Qwen3.8-27B-abliterated-120k",
            (reported as RouterStatusOutcome.Reported).load.loaded.single().id,
        )

        // NotOffered: the method is not composed — the one fallback case.
        assertEquals(
            RouterStatusOutcome.NotOffered,
            classifyRouterStatus(null, RouterCallFailure("http/404", "not found")),
        )

        // Refused: a verdict on the router. Falling back here would replace a real
        // "could not reach it" with a weaker answer.
        val refused = classifyRouterStatus(
            null,
            RouterCallFailure(
                "llm/model-discovery-rejected",
                "could not reach http://127.0.0.1:55599/v1/models",
            ),
        )
        assertEquals(
            RouterStatusOutcome.Refused("could not reach http://127.0.0.1:55599/v1/models"),
            refused,
        )

        // Refused, not NotOffered: a host that HAS the plugin but answers it wrongly
        // must not look like a host that does not have it, or nobody would learn the
        // plugin was broken.
        val unreadable = classifyRouterStatus(JSONObject("""{"unexpected":true}"""), null)
        assertTrue(unreadable is RouterStatusOutcome.Refused)
    }

    @Test
    fun `a status word the router invents is shown as itself rather than coerced`() {
        val unknown = RouterModelStatus(id = "m", status = "evicting")
        assertFalse(unknown.isLoaded)
        assertFalse(unknown.isLoading)
        assertFalse(unknown.isResident)
        // Case is not the router's contract; comparing it insensitively is ours.
        assertTrue(RouterModelStatus("m", "LOADED").isLoaded)
    }

    @Test
    fun `an unreported load is not the same fact as a reported empty one`() {
        // The whole reason `load` is nullable. A host that offers no status method
        // answers "not offered"; a router that lists nothing answers "nothing
        // listed". Collapsing them would let the tab claim a measurement it never
        // took.
        val notOffered = RouterProbeResult.Reachable(modelIds = listOf("m"), atMillis = 1L)
        assertNull(notOffered.load)

        val reportedEmpty = RouterLoad()
        assertTrue(reportedEmpty.models.isEmpty())
        assertTrue(reportedEmpty.loaded.isEmpty())
        assertTrue(reportedEmpty.processing.isEmpty())
    }

    @Test
    fun `the prefill remainder never goes negative`() {
        // Derived from two measured numbers, so it is clamped: a router that reports
        // more processed than total (a cache accounting quirk) must not yield a
        // negative token count in the UI.
        val over = RouterSlot(id = 0, isProcessing = true, promptTokens = 100, promptTokensProcessed = 140)
        assertEquals(0, over.promptTokensRemaining)
        // Neither half known: no derived number at all, rather than a zero.
        assertNull(RouterSlot(id = 0, isProcessing = false).promptTokensRemaining)
        assertNull(RouterSlot(id = 0, isProcessing = false, promptTokens = 100).promptTokensRemaining)
    }

    /**
     * The target for [ref] through a minimal choice, so the document's parsing and
     * the gate are exercised together rather than against a hand-built map.
     */
    private fun targetFor(ref: ModelRef): RouterTarget? = routerTargetOf(
        ModelChoice(ref = ref, source = ModelChoiceSource.SESSION_NEXT),
        providers,
    )

    /** The router report measured on 127.0.0.1:55555, trimmed to three models. */
    private val FULL_REPORT = """
    {
      "reachable": true,
      "checkedAt": 1791587528123,
      "role": "router",
      "maxInstances": 1,
      "modelsAutoload": true,
      "models": [
        { "id": "Qwen3.5-35B-A3B-UD-IQ4_XS", "status": "unloaded" },
        { "id": "Qwen3.8-27B-abliterated-120k", "status": "loaded",
          "contextSize": 122880, "childPort": 43723 },
        { "id": "Spark-X2.5-4B-Q4_K_M", "status": "loading" }
      ],
      "slots": {
        "model": "Qwen3.8-27B-abliterated-120k",
        "nCtx": 122880,
        "entries": [
          { "id": 0, "isProcessing": true, "promptTokens": 38893,
            "promptTokensProcessed": 1032, "promptTokensCache": 37639,
            "decoded": 222, "remaining": 16162 }
        ]
      }
    }
    """
}
