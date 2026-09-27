package com.hackathon.assistant.actions

import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.hackathon.assistant.core.ActionResult
import com.hackathon.assistant.core.Skill
import com.hackathon.assistant.core.SkillContext
import com.hackathon.assistant.core.SlotSpec
import java.util.Locale

/** Tells the user roughly where they are, using the phone's last known location. */
class LocationSkill : Skill {
    override val id = "my_location"
    override val description = "Tell the user roughly where they are right now"
    override val slots = emptyList<SlotSpec>()
    override val examples = listOf("where am I", "what's my current location", "which area am I in")

    override suspend fun execute(ctx: SkillContext, args: Map<String, String>): ActionResult {
        val location = lastKnownLocation(ctx)
            ?: return ActionResult.Failure("I couldn't get your location. Is location turned on?")

        describeAddress(ctx, location.latitude, location.longitude)?.let {
            return ActionResult.Success("You're near $it")
        }

        val lat = String.format(Locale.US, "%.4f", location.latitude)
        val lng = String.format(Locale.US, "%.4f", location.longitude)
        return ActionResult.Success("You're at latitude $lat, longitude $lng")
    }

    /** First non-null fix from GPS, then network. Returns null on any error or missing permission. */
    private fun lastKnownLocation(ctx: SkillContext): Location? = try {
        val lm = ctx.android.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.firstNotNullOfOrNull { provider ->
            try {
                lm?.getLastKnownLocation(provider)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Short, speakable address (thoroughfare / sub-locality / locality). Null if geocoding fails. */
    private fun describeAddress(ctx: SkillContext, lat: Double, lng: Double): String? = try {
        @Suppress("DEPRECATION")
        val results: List<Address>? = Geocoder(ctx.android, Locale.getDefault())
            .getFromLocation(lat, lng, 1)
        val address = results?.firstOrNull()
        val parts = listOfNotNull(
            address?.thoroughfare,
            address?.subLocality,
            address?.locality,
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        parts.joinToString(", ").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
