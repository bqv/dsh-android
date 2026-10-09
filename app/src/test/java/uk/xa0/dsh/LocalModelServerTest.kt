package uk.xa0.dsh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.model.HostModelSelection
import uk.xa0.dsh.model.LocalProbeRecord
import uk.xa0.dsh.model.LocalProvider
import uk.xa0.dsh.model.LocalServerProbeResult
import uk.xa0.dsh.model.LocalServerTarget
import uk.xa0.dsh.model.ModelChoice
import uk.xa0.dsh.model.ModelChoiceSource
import uk.xa0.dsh.model.ModelRef
import uk.xa0.dsh.model.ModelSelectionState
import uk.xa0.dsh.model.discoverModelsArgs
import uk.xa0.dsh.model.endpointHost
import uk.xa0.dsh.model.isLoopbackEndpoint
import uk.xa0.dsh.model.localServerFailureText
import uk.xa0.dsh.model.localServerTargetOf
import uk.xa0.dsh.model.parseLocalProviders

/**
 * The local-model-server tab's gate and its one live fact.
 *
 * The fixture below is the live host's `settings/describe` shape, trimmed: the
 * `llm-pi-ai` namespace's `providers` map with the two loopback profiles
 * (`dsh-local` → `127.0.0.1:55555`, `dsh-local-aux` → `127.0.0.1:55556`), one cloud
 * profile, and a profile that declares no endpoint. Field names, the
 * `input` / `defaultInput` split and the `headers.authorization` shape are all as
 * probed on 0.2.0-rc.2.
 *
 * The reachability half is tested for its *shape* here; its live behaviour was
 * probed by hand and is recorded in `model/LocalModelServer.kt`: a dead port answers
 * `llm/model-discovery-rejected` / `could not reach <url>`, and the running router
 * answers the model ids it advertises.
 */
class LocalModelServerTest {

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
        assertNull(localServerTargetOf(ModelChoice(ref = null, source = ModelChoiceSource.NONE), providers))
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
        assertNull(localServerTargetOf(choice, providers))
    }

    @Test
    fun `a pending switch to a local model opens it before the turn runs`() {
        val choice = ModelChoice(
            ref = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high"),
            source = ModelChoiceSource.SESSION_NEXT,
            lastUsed = ModelRef("deepseek-official", "deepseek-flash", "high"),
        )
        assertEquals("dsh-local", localServerTargetOf(choice, providers)?.provider?.id)
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
        val target = localServerTargetOf(choice, providers)
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
            LocalServerTarget(provider = provider, modelId = "m"),
        )
        assertFalse(args.getJSONObject("request").has("apiKey"))
    }

    @Test
    fun `a reachable answer knows whether it advertises the session's model`() {
        val reachable = LocalServerProbeResult.Reachable(
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
            localServerFailureText("llm/model-discovery-rejected", "could not reach http://127.0.0.1:55599/v1/models"),
        )
        // The carrier answers an unexposed method with a bare `not found` and HTTP
        // 404 — which is not about the server at all.
        assertEquals(
            "This host does not offer the model-discovery call to this client.",
            localServerFailureText("http/404", "not found"),
        )
        assertEquals(
            "This host does not offer the model-discovery call to this client.",
            localServerFailureText("", "not found"),
        )
        assertEquals("The host did not answer.", localServerFailureText("", "  "))
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

        fun state(ref: ModelRef, probe: LocalProbeRecord? = null) = UiState(
            currentSessionId = "s1",
            models = catalog,
            modelSelection = ModelSelectionState(host = HostModelSelection(lastUsed = ref, next = ref)),
            localServers = providers,
            localProbe = probe,
        )

        val localRef = ModelRef("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", "high")

        // A cloud route: no tab at all.
        assertNull(state(ModelRef("deepseek-official", "deepseek-flash", "high")).localServerTarget)
        // A local route: the tab, with the profile's endpoint.
        assertEquals("http://127.0.0.1:55555/v1", state(localRef).localServerTarget?.endpoint)

        // A check made for another route must not be drawn beside this one.
        val other = LocalProbeRecord("dsh-local-aux", "Qwen2.5-0.5B-Instruct-Q8_0", LocalServerProbeResult.Reachable(listOf("x"), 5L))
        assertEquals(LocalServerProbeResult.Idle, state(localRef, other).localServerProbe)
        // …nor one made for another model of the same provider.
        val otherModel = LocalProbeRecord("dsh-local", "Qwen3.8-27B-UD-Q4_K_M", LocalServerProbeResult.Reachable(listOf("x"), 5L))
        assertEquals(LocalServerProbeResult.Idle, state(localRef, otherModel).localServerProbe)
        // …while the matching one is shown, timestamp and all.
        val mine = LocalProbeRecord("dsh-local", "Qwen3.5-35B-A3B-UD-IQ4_XS-240k", LocalServerProbeResult.Reachable(listOf("y"), 5L))
        assertEquals(LocalServerProbeResult.Reachable(listOf("y"), 5L), state(localRef, mine).localServerProbe)

        // No document read yet: the tab is closed, because "not loaded" is not "local".
        val unread = UiState(
            currentSessionId = "s1",
            models = catalog,
            modelSelection = ModelSelectionState(host = HostModelSelection(localRef, localRef)),
        )
        assertNull(unread.localServerTarget)
        assertEquals(LocalServerProbeResult.Idle, unread.localServerProbe)
    }

    /**
     * The target for [ref] through a minimal choice, so the document's parsing and
     * the gate are exercised together rather than against a hand-built map.
     */
    private fun targetFor(ref: ModelRef): LocalServerTarget? = localServerTargetOf(
        ModelChoice(ref = ref, source = ModelChoiceSource.SESSION_NEXT),
        providers,
    )
}
