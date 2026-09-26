package uk.xa0.dsh.model

/**
 * The label one session row wears in the drawer.
 *
 * This is the web client's `displayTitleOf` (session-controller's
 * `client/sessions/service.ts`) — the durable title, else the project directory's
 * basename, else the session id — plus the one thing that file gets to decide
 * locally: when the "New Session" placeholder applies.
 *
 * That placeholder is the web's substitute for a row whose `title` is **null**,
 * and a blank row normally carries exactly that. It is not the same fact as
 * "there is no stored title": the host shell's bootstrap renames the session it
 * creates (`session/rename`), and such a session is still `blank` because no user
 * message was ever sent to it. Testing `blank` first therefore threw away a name
 * that had been set on purpose — the archived shell read as "New Session" in the
 * drawer while the host reported its title. A stored name is an explicit act, so
 * it outranks the placeholder.
 *
 * @param subagentLabel the child's label from a catalog this client holds, if any
 * @param stored the durable title from the roster's projections
 * @param blank the host's own "no user message yet" flag
 * @param directoryName `basename(cwd)`, already shortened, when the row has one
 * @param id the session id, the last resort
 */
fun sessionRowTitle(
    subagentLabel: String?,
    stored: String?,
    blank: Boolean,
    directoryName: String?,
    id: String,
): String = when {
    subagentLabel != null && subagentLabel.isNotBlank() -> subagentLabel
    stored != null && stored.isNotBlank() -> stored
    blank -> "New Session"
    directoryName != null && directoryName.isNotEmpty() -> directoryName
    else -> id
}
