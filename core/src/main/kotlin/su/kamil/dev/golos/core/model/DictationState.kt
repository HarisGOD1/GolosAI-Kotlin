package su.kamil.dev.golos.core.model

/**
 * Lifecycle states of the speech dictation system.
 */
enum class DictationState {
    /** System is waiting for push-to-talk trigger. */
    IDLE,

    /** Push-to-talk key is actively held down; microphone audio is streaming into buffer. */
    RECORDING,

    /** Push-to-talk key released; audio is being transcribed and injected into target text field. */
    PROCESSING,
}
