package su.kamil.dev.golos.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import su.kamil.dev.golos.core.model.DictationState
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe state machine managing transitions between IDLE, RECORDING, and PROCESSING.
 */
class DictationStateMachine {
    private val lock = ReentrantLock()
    private val _state = MutableStateFlow(DictationState.IDLE)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    /**
     * Transition from IDLE to RECORDING when push-to-talk key is depressed.
     * Returns true if transition succeeded, false if invalid state.
     */
    fun startRecording(): Boolean =
        lock.withLock {
            if (_state.value == DictationState.IDLE) {
                _state.value = DictationState.RECORDING
                true
            } else {
                false
            }
        }

    /**
     * Transition from RECORDING to PROCESSING when push-to-talk key is released.
     * Returns true if transition succeeded, false if invalid state.
     */
    fun startProcessing(): Boolean =
        lock.withLock {
            if (_state.value == DictationState.RECORDING) {
                _state.value = DictationState.PROCESSING
                true
            } else {
                false
            }
        }

    /**
     * Transition from PROCESSING to IDLE once transcription and injection are done.
     */
    fun finishProcessing(): Boolean =
        lock.withLock {
            if (_state.value == DictationState.PROCESSING) {
                _state.value = DictationState.IDLE
                true
            } else {
                false
            }
        }

    /**
     * Reset state directly to IDLE (e.g., error recovery or user cancellation).
     */
    fun reset(): Unit =
        lock.withLock {
            _state.value = DictationState.IDLE
        }
}
