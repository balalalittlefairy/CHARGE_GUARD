package io.github.chargercontroller.core

/** Continuous hysteresis policy. All events and command completions are serialized by the service. */
class ChargingCycle @JvmOverloads constructor(val threshold: Int, val lowerThreshold: Int = 30) {
    enum class Phase { PREPARING, TURNING_ON, MONITORING, WAITING_LOW, TURNING_OFF,
        RETRY_ON, RETRY_OFF, WAIT_RETRY_ON, WAIT_RETRY_OFF, FAILED, STOPPED }
    enum class Command { NONE, ON, OFF }
    var phase = Phase.PREPARING; private set
    var percent: Int? = null; private set
    var attempts = 0; private set
    var offRequested = false; private set
    var roundPercent: Int? = null; private set
    val nextRetryPercent get() = roundPercent?.let { if (offRequested) it + 3 else it - 1 }
    val active get() = phase != Phase.FAILED && phase != Phase.STOPPED

    init { require(threshold in 2..100 && lowerThreshold in 1 until threshold) }

    fun battery(value: Int?): Command {
        if (!active) return Command.NONE
        percent = value?.takeIf { it in 0..100 }
        val p = percent ?: return Command.NONE
        return when (phase) {
            Phase.MONITORING -> if (p >= threshold) issue(false) else Command.NONE
            Phase.WAITING_LOW -> if (p <= lowerThreshold) issue(true) else Command.NONE
            Phase.WAIT_RETRY_ON -> when {
                p >= threshold -> issue(false)
                p <= lowerThreshold && p <= (nextRetryPercent ?: -1) -> issue(true)
                else -> Command.NONE
            }
            Phase.WAIT_RETRY_OFF -> when {
                p == 100 -> { phase = Phase.FAILED; Command.NONE }
                p <= lowerThreshold -> issue(true)
                p >= (nextRetryPercent ?: 101) -> issue(false)
                else -> Command.NONE
            }
            Phase.RETRY_OFF -> { if (p == 100) phase = Phase.FAILED; Command.NONE }
            else -> Command.NONE // Never overlap an in-flight operation.
        }
    }

    /** Both startup and restart inspect the relay. Persisted round budgets survive restarts. */
    @JvmOverloads fun recover(value: Int?, power: Boolean?, hadRequestedOff: Boolean,
        previousAttempts: Int = 0, previousPhase: String? = null, previousRoundPercent: Int? = null): Command {
        check(phase == Phase.PREPARING)
        percent = value?.takeIf { it in 0..100 }
        if (percent == null) { phase = Phase.FAILED; return Command.NONE }
        val previous = Phase.entries.firstOrNull { it.name == previousPhase }
        val pending = previous in listOf(Phase.TURNING_ON, Phase.RETRY_ON, Phase.WAIT_RETRY_ON,
            Phase.TURNING_OFF, Phase.RETRY_OFF, Phase.WAIT_RETRY_OFF) || hadRequestedOff
        if (pending) {
            offRequested = hadRequestedOff
            attempts = previousAttempts.coerceIn(0, 3)
            roundPercent = previousRoundPercent?.takeIf { it in 0..100 } ?: percent
            if (power != null && power == !offRequested) return confirmed()
            if (attempts >= 3 || previous == Phase.WAIT_RETRY_ON || previous == Phase.WAIT_RETRY_OFF) {
                phase = if (offRequested) Phase.WAIT_RETRY_OFF else Phase.WAIT_RETRY_ON
                return battery(percent)
            }
            phase = if (offRequested) Phase.RETRY_OFF else Phase.RETRY_ON
            return retry()
        }
        offRequested = false
        if (percent!! >= threshold) {
            if (power == false) { phase = Phase.WAITING_LOW; return Command.NONE }
            return issue(false)
        }
        if (percent!! <= lowerThreshold) {
            if (power == true) { phase = Phase.MONITORING; return Command.NONE }
            return issue(true)
        }
        if (power == null) { phase = Phase.FAILED; return Command.NONE }
        phase = if (power) Phase.MONITORING else Phase.WAITING_LOW
        return Command.NONE
    }

    private fun issue(on: Boolean): Command {
        attempts = 1; roundPercent = percent; offRequested = !on
        phase = if (on) Phase.TURNING_ON else Phase.TURNING_OFF
        return if (on) Command.ON else Command.OFF
    }
    private fun confirmed(): Command {
        val on = !offRequested
        phase = if (on) Phase.MONITORING else Phase.WAITING_LOW
        offRequested = false; roundPercent = null; attempts = 0
        return battery(percent)
    }
    fun result(confirmed: Boolean): Command {
        if (phase != Phase.TURNING_ON && phase != Phase.TURNING_OFF) return Command.NONE
        if (!offRequested && percent != null && percent!! >= threshold) return issue(false)
        if (confirmed) return confirmed()
        if (offRequested && percent == 100) { phase = Phase.FAILED; return Command.NONE }
        phase = if (attempts >= 3) {
            if (offRequested) Phase.WAIT_RETRY_OFF else Phase.WAIT_RETRY_ON
        } else if (offRequested) Phase.RETRY_OFF else Phase.RETRY_ON
        return Command.NONE
    }
    fun retry(): Command {
        if (phase != Phase.RETRY_ON && phase != Phase.RETRY_OFF) return Command.NONE
        if (!offRequested && percent != null && percent!! >= threshold) return issue(false)
        if (offRequested && percent == 100) { phase = Phase.FAILED; return Command.NONE }
        attempts++; phase = if (offRequested) Phase.TURNING_OFF else Phase.TURNING_ON
        return if (offRequested) Command.OFF else Command.ON
    }
    fun stop() { phase = Phase.STOPPED }
    fun fail() { phase = Phase.FAILED }
    companion object {
        fun percentage(level: Int, scale: Int): Int? =
            if (scale <= 0 || level < 0 || level > scale) null else (level.toLong() * 100 / scale).toInt()
    }
}
