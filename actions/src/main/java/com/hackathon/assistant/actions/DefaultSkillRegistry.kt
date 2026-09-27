package com.hackathon.assistant.actions

import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillRegistry

/** All registered skills. Owner: actions. Add new skills to the list below. */
class DefaultSkillRegistry : SkillRegistry {
    private val skills: List<Skill> = listOf<Skill>(
        OpenAppSkill(),
        AppSkills.listApps,
        AppSkills.openLink,
        SmsReaderSkill(),
        WhatsAppSkill(),
        // Wave 1 Android-API skills
        CalendarSkill(),
        ContactLookupSkill(),
        ClipboardSkill(),
        CallLogSkill(),
        LocationSkill(),
        DeviceStatusSkill(),
        MediaControlSkill(),
        PlayMusicSkill(),
        InstallAppSkill(),
        OrderItemSkill(),
        // (list_apps is provided by AppSkills.listApps above)
        // Wave 2 action skills
        EmailSkill(),
        AddCalendarEventSkill(),
        ShareTextSkill(),
        CalculatorSkill(),
        RingerControlSkill(),
    ) + Skills.all + MemorySkills.all
    private val byId = skills.associateBy { it.id }

    override fun all() = skills
    override fun get(id: String) = byId[id]
}
