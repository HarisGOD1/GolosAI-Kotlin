package su.kamil.dev.golos.system.keyboard

import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.GlobalHotkeyHook

/**
 * Programmatic hotkey hook for testing and headless runs.
 */
class SimulatedHotkeyHook : GlobalHotkeyHook {

    private var onKeyDownCallback: (() -> Unit)? = null
    private var onKeyUpCallback: (() -> Unit)? = null
    private var registered = false

    override val isRegistered: Boolean
        get() = registered

    override fun register(
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit
    ): Result<Unit> {
        this.onKeyDownCallback = onKeyDown
        this.onKeyUpCallback = onKeyUp
        this.registered = true
        return Result.success(Unit)
    }

    override fun unregister() {
        registered = false
        onKeyDownCallback = null
        onKeyUpCallback = null
    }

    fun triggerKeyDown() {
        if (registered) onKeyDownCallback?.invoke()
    }

    fun triggerKeyUp() {
        if (registered) onKeyUpCallback?.invoke()
    }
}
