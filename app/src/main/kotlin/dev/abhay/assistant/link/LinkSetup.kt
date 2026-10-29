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
    // Off is set up and switched off: Ferb Link hides its switch until it's paired.
    val paired = status is LinkStatus.Ready || status == LinkStatus.Off
    val working = status is LinkStatus.Ready && answers == true
    if (working) return emptyList()
    return listOfNotNull(
        SetupStep("Install Ferb Link", "From the same place as Ferb.",
            done = installed, action = SetupAction.Download.takeUnless { installed }),
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
