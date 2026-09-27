package com.hackathon.assistant.actions

import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec

/** Reports storage, network and memory at a glance, or just one aspect on request. */
class DeviceStatusSkill : Skill {
    override val id = "device_status"
    override val description = "Report free storage, network connection and free memory"
    override val slots = listOf(
        SlotSpec(
            name = "aspect",
            description = "Which part to report: storage, network or memory. Omit to report all.",
            required = false,
            question = "Storage, network or memory?",
        ),
    )
    override val examples = listOf(
        "how much storage is left",
        "am I on wifi",
        "how much free space",
        "device status",
    )

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult = try {
        val aspect = args["aspect"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val parts = when {
            aspect == null -> listOf(storage(), network(ctx), memory(ctx))
            aspect.contains("storage") || aspect.contains("space") || aspect.contains("disk") ->
                listOf(storage())
            aspect.contains("network") || aspect.contains("wifi") ||
                aspect.contains("wi-fi") || aspect.contains("data") || aspect.contains("internet") ->
                listOf(network(ctx))
            aspect.contains("memory") || aspect.contains("ram") ->
                listOf(memory(ctx))
            else -> listOf(storage(), network(ctx), memory(ctx))
        }
        ActionResult.Success(parts.joinToString(" "))
    } catch (e: Exception) {
        ActionResult.Failure("Couldn't read the device status: ${e.message}")
    }

    private fun storage(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        return "You have ${gb(stat.availableBytes)} gigabytes of storage free out of ${gb(stat.totalBytes)}."
    }

    private fun network(ctx: SkillContext): String {
        val cm = ctx.android.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return when {
            caps == null -> "You're not connected to any network."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "You're connected to Wi-Fi."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "You're on mobile data."
            else -> "You're connected to a network."
        }
    }

    private fun memory(ctx: SkillContext): String {
        val am = ctx.android.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return "And ${gb(info.availMem)} gigabytes of memory is free out of ${gb(info.totalMem)}."
    }

    /** Bytes as gigabytes with one decimal, for a natural spoken sentence. */
    private fun gb(bytes: Long): String = String.format("%.1f", bytes / 1_073_741_824.0)
}
