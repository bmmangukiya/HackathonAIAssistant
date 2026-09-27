package com.hackathon.assistant.peers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent-to-agent link between phones running this app, over Google Nearby Connections
 * (Bluetooth + Wi-Fi, no internet). First contact needs the 4-digit code confirmed on BOTH
 * phones; afterwards the peer is trusted and reconnects automatically.
 * Messages: {"t":"task"|"reply"|"file", ...} as bytes; photos as file payloads.
 */
class PeerLink(private val context: Context, private val scope: CoroutineScope, private val ownerName: () -> String) {

    /** Incoming pairing request from an unknown phone: the app asks its user (agent run). */
    var onPairRequest: (name: String, code: String) -> Unit = { _, _ -> }
    /** A trusted phone's agent asks this phone to do something; return the spoken result. */
    var onTask: suspend (from: String, text: String) -> String = { _, _ -> "" }
    /** Photos arrived and were saved to the gallery. */
    var onPhotos: (from: String, count: Int) -> Unit = { _, _ -> }

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val prefs by lazy { context.getSharedPreferences("peers", Context.MODE_PRIVATE) }
    private val discovered = ConcurrentHashMap<String, String>()   // endpointId -> name
    private val connected = ConcurrentHashMap<String, String>()    // endpointId -> name
    private val pending = ConcurrentHashMap<String, String>()      // endpointId -> name, awaiting pair_reply
    private val connectedEvents = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val replies = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val incomingFiles = ConcurrentHashMap<Long, Payload>()
    private val fileNames = ConcurrentHashMap<Long, String>()
    private val received = ConcurrentHashMap<String, Int>()        // sender -> photos saved in this batch
    private var codeWaiter: CompletableDeferred<Pair<String, String>>? = null

    private fun trusted(name: String) = prefs.getBoolean("trusted:$name", false)
    fun trustedNames(): List<String> = prefs.all.keys.filter { it.startsWith("trusted:") }.map { it.removePrefix("trusted:") }
    fun connectedNames(): List<String> = connected.values.toList()

    fun start() {
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(ownerName(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { Log.w(TAG, "advertising failed", it) }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { Log.w(TAG, "discovery failed", it) }
        Log.i(TAG, "advertising as \"${ownerName()}\"; trusted: ${trustedNames()}")
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            discovered[id] = info.endpointName
            Log.i(TAG, "found ${info.endpointName}")
            // Trusted phones reconnect by themselves; the lower name dials to avoid both dialling.
            if (trusted(info.endpointName) && !connected.containsKey(id) && ownerName() < info.endpointName) {
                client.requestConnection(ownerName(), id, lifecycle)
            }
        }
        override fun onEndpointLost(id: String) { discovered.remove(id) }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            val name = info.endpointName
            if (trusted(name)) {
                client.acceptConnection(id, payloads)
                return
            }
            pending[id] = name
            val code = info.authenticationDigits
            Log.i(TAG, "pairing with $name, code $code (incoming=${info.isIncomingConnection})")
            val waiter = codeWaiter
            if (waiter != null && !info.isIncomingConnection) waiter.complete(name to code)   // we dialled: our pair_device waits
            else onPairRequest(name, code)                                                   // they dialled: ask our user
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            val name = pending.remove(id) ?: discovered[id] ?: "phone"
            val ok = result.status.isSuccess
            if (ok) {
                connected[id] = name
                prefs.edit().putBoolean("trusted:$name", true).apply()
            }
            Log.i(TAG, "connection with $name: ${if (ok) "connected" else "failed ${result.status}"}")
            connectedEvents.remove(name)?.complete(ok)
        }

        override fun onDisconnected(id: String) { connected.remove(id) }
    }

    /** Dials a nearby phone whose name matches; returns (name, code) for the user to confirm. */
    suspend fun pair(nameHint: String): Pair<String, String>? {
        val id = withTimeoutOrNull(20_000) {
            var found: String? = null
            while (found == null) { found = match(discovered, nameHint); if (found == null) delay(500) }
            found
        } ?: return null
        val waiter = CompletableDeferred<Pair<String, String>>()
        codeWaiter = waiter
        client.requestConnection(ownerName(), id, lifecycle)
        return withTimeoutOrNull(20_000) { waiter.await() }.also { codeWaiter = null }
    }

    /** The user's answer about the code; both phones must accept. Returns true once connected. */
    suspend fun reply(accept: Boolean): Boolean {
        val (id, name) = pending.entries.firstOrNull()?.toPair() ?: return false
        if (!accept) { client.rejectConnection(id); pending.remove(id); return false }
        val done = CompletableDeferred<Boolean>().also { connectedEvents[name] = it }
        client.acceptConnection(id, payloads)
        return withTimeoutOrNull(45_000) { done.await() } ?: false
    }

    /** Sends a task to a connected phone's agent and waits for its spoken result. */
    suspend fun ask(device: String, text: String): String? {
        val id = ensureConnected(device) ?: return null
        val msgId = UUID.randomUUID().toString()
        val wait = CompletableDeferred<String>().also { replies[msgId] = it }
        send(id, JSONObject().put("t", "task").put("id", msgId).put("text", text))
        return withTimeoutOrNull(180_000) { wait.await() }
    }

    /** Sends image files; returns how many were queued. */
    suspend fun sendPhotos(device: String, uris: List<Uri>): Int {
        val id = ensureConnected(device) ?: return -1
        var n = 0
        for (u in uris) {
            val pfd = runCatching { context.contentResolver.openFileDescriptor(u, "r") }.getOrNull() ?: continue
            val payload = Payload.fromFile(pfd)
            // Keep the real name/extension (screenshots are PNG, camera photos JPEG).
            val original = runCatching {
                context.contentResolver.query(u, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            val name = original ?: "photo_${System.currentTimeMillis()}_$n.jpg"
            send(id, JSONObject().put("t", "file").put("pid", payload.id).put("name", name).put("total", uris.size))
            client.sendPayload(id, payload)
            n++
        }
        return n
    }

    /** Connected endpoint for a trusted/nearby device name, reconnecting if needed. */
    private suspend fun ensureConnected(device: String): String? {
        match(connected, device)?.let { return it }
        val name = (trustedNames() + discovered.values).firstOrNull { it.contains(device, true) || device.contains(it, true) } ?: return null
        if (!trusted(name)) return null
        val id = withTimeoutOrNull(20_000) {
            var found: String? = null
            while (found == null) { found = match(discovered, name); if (found == null) delay(500) }
            found
        } ?: return null
        val done = CompletableDeferred<Boolean>().also { connectedEvents[name] = it }
        client.requestConnection(ownerName(), id, lifecycle)
        return if (withTimeoutOrNull(20_000) { done.await() } == true) id else null
    }

    private fun match(map: Map<String, String>, hint: String): String? {
        val h = hint.lowercase().removeSuffix("'s phone").removeSuffix(" phone").trim()
        return map.entries.firstOrNull { it.value.lowercase().contains(h) || h.contains(it.value.lowercase()) }?.key
            ?: if (h.isBlank() || map.size == 1) map.keys.firstOrNull() else null
    }

    private fun send(id: String, json: JSONObject) {
        client.sendPayload(id, Payload.fromBytes(json.toString().toByteArray()))
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val from = connected[id] ?: discovered[id] ?: "phone"
            when (payload.type) {
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
                Payload.Type.BYTES -> {
                    val json = JSONObject(String(payload.asBytes() ?: return))
                    when (json.optString("t")) {
                        "task" -> scope.launch {
                            val result = runCatching { onTask(from, json.optString("text")) }.getOrElse { "Sorry, that failed." }
                            send(id, JSONObject().put("t", "reply").put("id", json.optString("id")).put("text", result))
                        }
                        "reply" -> replies.remove(json.optString("id"))?.complete(json.optString("text"))
                        "file" -> fileNames[json.optLong("pid")] = json.optString("name")
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.SUCCESS) return
            val payload = incomingFiles.remove(update.payloadId) ?: return
            val from = connected[id] ?: "phone"
            scope.launch {
                if (savePhoto(payload, fileNames.remove(update.payloadId) ?: "photo.jpg", from)) {
                    val n = (received[from] ?: 0) + 1
                    received[from] = n
                    // Announce once the burst of transfers goes quiet.
                    delay(2_500)
                    if (received[from] == n) { received.remove(from); onPhotos(from, n) }
                }
            }
        }
    }

    private fun savePhoto(payload: Payload, name: String, from: String): Boolean = runCatching {
        val src = payload.asFile()?.asUri() ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Assistant/${from.replace(Regex("[^A-Za-z0-9 _-]"), "")}")
        }
        val dst = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openInputStream(src)!!.use { i -> context.contentResolver.openOutputStream(dst)!!.use { o -> i.copyTo(o) } }
        runCatching { context.contentResolver.delete(src, null, null) }
        true
    }.onFailure { Log.w(TAG, "saving photo failed", it) }.getOrDefault(false)

    private companion object {
        const val TAG = "PeerLink"
        const val SERVICE_ID = "com.hackathon.assistant.peer"
    }
}
