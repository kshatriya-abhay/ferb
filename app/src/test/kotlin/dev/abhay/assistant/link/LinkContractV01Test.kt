package dev.abhay.assistant.link

/**
 * Every contract test against Ferb Link 0.1's replies (its contract/v1 at the v0.1 tag, in
 * ferb-link-contract/v0.1, from before jev_capped): Ferb 0.2 works with Ferb Link 0.1 too.
 */
class LinkContractV01Test : LinkContractTest() {
    override val set: String get() = "v0.1"
}
