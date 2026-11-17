package dev.abhay.assistant.link

/** What a step of setting up Ferb Link lets the user do from Ferb. */
enum class SetupAction { Download, Guide, OpenFerbLink }

/** One step of setting up Ferb Link, ticked when Ferb can tell it's done. */
data class SetupStep(val title: String, val detail: String, val done: Boolean, val action: SetupAction? = null)

/**
 * The checklist on Ferb's Ferb Link page, from what Ferb can see: Ferb Link's [status] (installed,
 * on, set up) and whether the computer [answers] Ferb's check (null: not checked yet). It ticks
 * itself off as the page comes back to the front. Empty once everything works.
 */
fun setupSteps(status: LinkStatus, answers: Boolean?): List<SetupStep> {
    val installed = status != LinkStatus.NotInstalled
    blocked(status)?.let { return listOf(install(done = true), it) }
    // Off is set up and switched off: Ferb Link hides its switch until it's paired.
    val paired = status is LinkStatus.Ready || status == LinkStatus.Off
    val working = status is LinkStatus.Ready && answers == true
    if (working) return emptyList()
    return listOfNotNull(
        install(done = installed),
        SetupStep("Set up its server on your computer", "Needs a GPU. See the quick start.",
            done = paired, action = SetupAction.Guide),
        SetupStep("Tailscale on this phone and the computer", "Same tailnet. Allow Ferb Link in Tailscale's app filter.",
            done = answers == true),
        SetupStep("Connect to your computer", "Scan the QR code it shows, in Ferb Link.",
            done = paired, action = SetupAction.OpenFerbLink.takeIf { installed && !paired }),
        SetupStep("Turn on “Answer Ferb's requests”", "In Ferb Link.", done = false, action = SetupAction.OpenFerbLink)
            .takeIf { status == LinkStatus.Off },
        SetupStep("Your computer answers", if (answers == false) "No answer: check Tailscale and the server." else "Checked when this page opens.",
            done = answers == true),
    )
}

private fun install(done: Boolean) =
    SetupStep("Install Ferb Link", "From the same place as Ferb.", done = done, action = SetupAction.Download.takeUnless { done })

/**
 * A Ferb Link that's installed but can't be used: one step, with its own fix. Ferb can't see whether
 * it's paired, and pairing again fixes none of these (review P2-114).
 */
private fun blocked(status: LinkStatus): SetupStep? = when (status) {
    LinkStatus.NotAllowed -> SetupStep("Install Ferb and Ferb Link from the same place",
        "Ferb Link only answers a Ferb signed with the same key: reinstall the one you got elsewhere.", done = false, action = SetupAction.Download)
    is LinkStatus.Mismatch ->
        if (status.version < LinkContract.VERSION) SetupStep("Update Ferb Link",
            "It's older than this Ferb (version ${status.version}, Ferb ${LinkContract.VERSION}).", done = false, action = SetupAction.Download)
        else SetupStep("Update Ferb", "It's older than this Ferb Link (version ${status.version}, Ferb ${LinkContract.VERSION}).", done = false)
    is LinkStatus.Failed -> SetupStep("Open Ferb Link", "It failed: ${status.reason}. Opening it starts it again.", done = false, action = SetupAction.OpenFerbLink)
    else -> null
}
