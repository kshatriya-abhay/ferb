package dev.abhay.assistant

/**
 * A device test the user would notice: a screen in front, a notification, the mic, or a volume
 * change. `QUIET=1 scripts/device-check.sh` (a phone in use) leaves these out (review P2-171:
 * it left out one method and still opened the sheet, recorded and notified).
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class InFront
