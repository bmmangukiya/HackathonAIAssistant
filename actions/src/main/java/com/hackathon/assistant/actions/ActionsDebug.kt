package com.hackathon.assistant.actions

/**
 * Public debug facade for the :actions module.
 *
 * Keeps internals (SmsRepository etc.) encapsulated while giving the :app debug
 * receiver a clean, minimal API for demo/testing on devices without real data.
 */
object ActionsDebug {

    /**
     * Load genuine-looking demo SMS (for SIM-less test devices) or clear them.
     * When loaded, [SmsReaderSkill] reads these instead of the real SMS provider.
     */
    fun seedSms(enabled: Boolean) {
        SmsRepository.demoOverride = if (enabled) SmsRepository.sampleMessages() else null
    }
}
