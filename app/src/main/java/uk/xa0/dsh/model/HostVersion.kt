package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * The DSH version of the host this app is connected to, as that host named it.
 *
 * It is **the host's** version, never this app's own package or build constant: the
 * app talks to a remote machine and the two releases move independently, so a wrong
 * number here is worse than none — it is confidently actionable while somebody is
 * reporting a bug.
 *
 * ## Where a version can come from
 *
 * The `$events` logical stream opens with one `ready` item, and that item is the only
 * host-identity frame on the wire. There is no `hello`/`version` Remote method in the
 * descriptor set and no version header on the mux handshake. On 0.2.0-rc.2 the item
 * is `{"type":"ready","clientId":"…","host":{"home":"/home/user"}}`: the gateway
 * builds it as `{ ...ready, clientId, host: registration.host }`, the registration is
 * `{ home: host.home }` (`dsh-api-gateway/lib/index.js`, `openRemoteEvents`), and the
 * source is `{ home: homedir() }` (`dsh-api-remotes/lib/index.js`). So the frame this
 * app already receives carries **no** version, and [NotReported] is the state every
 * shipped host reaches.
 *
 * The same `host` object is read here for a `version` anyway, because that is the one
 * field a host would have to start sending and it is the frame the app already folds.
 * Nothing is inferred from the app's own package, a build constant, or a guess.
 *
 * ## Why three states rather than a nullable string
 *
 * [Unknown] and [NotReported] are different facts and the About page says so
 * differently: *Unknown* is "nothing has been heard on this connection" — before the
 * first connect, while disconnected, or in the moment after a reconnect — and *Not
 * reported* is "the host's opening frame arrived and named no version". Collapsing
 * them would either claim the host said nothing when it had said nothing yet, or leave
 * a previous host's version on screen after the socket moved, which is the same lie as
 * a stale route.
 */
sealed interface HostVersion {
    /** No `ready` frame has arrived on this connection, so nothing may be named. */
    data object Unknown : HostVersion

    /** The opening frame arrived and carried no usable version. */
    data object NotReported : HostVersion

    /** The host named this version in its opening frame. */
    data class Reported(val version: String) : HostVersion
}

/**
 * Read the host's own version out of the `$events` opening `ready` item.
 *
 * [frame] is the item's `value`, the object `RemoteMux` reads as `{"type":"ready",…}`.
 * A frame that carries no `host` object, no `version` inside it, or a `version` that is
 * not a non-blank string, is [HostVersion.NotReported]: the host spoke and did not name
 * one. It is deliberately never [HostVersion.Unknown] — the caller folds a frame only
 * once it has arrived, and [HostVersion.Unknown] is the caller's state before that.
 */
fun hostVersionFromReadyFrame(frame: JSONObject): HostVersion {
    val host = frame.optJSONObject("host") ?: return HostVersion.NotReported
    // `opt` rather than `optString`: a JSON null and a non-string both have to read as
    // "no version" rather than as the literal text "null".
    val version = host.opt("version") as? String ?: return HostVersion.NotReported
    return version.trim().takeIf { it.isNotEmpty() }
        ?.let { HostVersion.Reported(it) }
        ?: HostVersion.NotReported
}
