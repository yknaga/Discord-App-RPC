package rpc.gateway

import com.my.rpc.domain.interfaces.Logger
import com.my.rpc.domain.interfaces.NoOpLogger
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import rpc.gateway.entities.Heartbeat
import rpc.gateway.entities.Identify.Companion.toIdentifyPayload
import rpc.gateway.entities.OutgoingPayload
import rpc.gateway.entities.Payload
import rpc.gateway.entities.PayloadData
import rpc.gateway.entities.Ready
import rpc.gateway.entities.Resume
import rpc.gateway.entities.op.OpCode
import rpc.gateway.entities.op.OpCode.*
import rpc.gateway.entities.presence.Presence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

open class DiscordWebSocketImpl(
    private val token: String,
    private val logger: Logger = NoOpLogger,
    private val deviceName: String = "Android"
) : DiscordWebSocket {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"

    @Volatile
    private var websocket: DefaultClientWebSocketSession? = null

    @Volatile
    private var sequence = 0

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var heartbeatInterval = 0L

    @Volatile
    private var resumeGatewayUrl: String? = null

    private var heartbeatJob: Job? = null

    @Volatile
    private var connected = false

    @Volatile
    private var heartbeatAcknowledged = true

    private val sendMutex = Mutex()

    /**
     * Signalled when the socket is authenticated (READY received).
     *
     * Recreated for every connection attempt (see [resetConnectionSignals]).
     * Previously these were created once and stayed completed forever, so
     * after the socket was lost, sendActivity() still believed the gateway
     * was ready and send() dropped every PRESENCE_UPDATE with
     * "socketPresent=false, socketActive=null".
     */
    @Volatile
    private var connectionReady = CompletableDeferred<Unit>()

    /** Signalled after Discord sends the initial session list. */
    @Volatile
    private var initialSessionSyncReceived = CompletableDeferred<Unit>()

    private fun resetConnectionSignals() {
        connectionReady = CompletableDeferred()
        initialSessionSyncReceived = CompletableDeferred()
    }

    private var client: HttpClient = HttpClient {
        install(WebSockets)
    }

    /**
     * Set once [close] has closed [client]. A closed Ktor HttpClient can
     * never be used again, so [connect] recreates it when this is true.
     * Without this, the first presence clear (closeRPC) permanently broke
     * reconnecting: relaunching the detected app afterwards could never
     * establish a new Gateway connection on the same instance.
     */
    @Volatile
    private var clientClosed = false
    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Default

    override suspend fun connect() {
        coroutineContext.cancelChildren() // Cancel any previous connection attempts
        resetConnectionSignals()
        if (clientClosed) {
            client = HttpClient {
                install(WebSockets)
            }
            clientClosed = false
        }
        launch {
            try {
                logger.i("Gateway", "Connect called")
                val url = resumeGatewayUrl ?: gatewayUrl
                websocket = client.webSocketSession(url)

                // start receiving messages
                websocket!!.incoming.receiveAsFlow()
                    .collect {
                        when (it) {
                            is Frame.Text -> {
                                val jsonString = it.readText()
                                onMessage(jsonString)
                            }
                            else -> {}
                        }
                    }
                handleClose()
            } catch (e: Exception) {
                logger.e("Gateway", e.message ?: "")
                close()
            }
        }
    }

    /** Recoverable close codes where we should reconnect. */
    private fun isRecoverableCloseCode(code: Int): Boolean {
        return code in setOf(4000, 4001, 4002, 4003, 4005, 4007, 4009) || code in 1000..1001
    }

    private suspend fun handleClose() {
        heartbeatJob?.cancel()
        connected = false
        resetConnectionSignals()
        val close = websocket?.closeReason?.await()
        val closeCode = close?.code?.toInt() ?: -1
        logger.w(
            "Gateway", "Closed with code: ${close?.code}, " +
                    "reason: ${close?.message}, " +
                    "can_reconnect: ${isRecoverableCloseCode(closeCode)}"
        )
        if (isRecoverableCloseCode(closeCode)) {
            delay(200.milliseconds)
            connect()
        } else
            close()
    }

    private suspend fun onMessage(jsonString: String) {
        val payload = json.decodeFromString<Payload>(jsonString)
        logger.d("Gateway", "Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch(jsonString)
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> handleHello(jsonString)
            HEARTBEAT_ACK -> {
                heartbeatAcknowledged = true
                logger.d("Gateway", "Heartbeat ACK received")
            }
            else -> {}
        }
    }

    /** Log only non-sensitive fields from presence/session dispatches. */
    private fun primitiveValue(obj: JsonObject, key: String): String =
        runCatching { obj[key]?.jsonPrimitive?.contentOrNull ?: "null" }
            .getOrDefault("<invalid>")

    private fun presenceActivitySummary(element: JsonElement): String = runCatching {
        val activity = element.jsonObject
        "name=${primitiveValue(activity, "name")}, " +
            "type=${primitiveValue(activity, "type")}, " +
            "applicationId=${primitiveValue(activity, "application_id")}, " +
            "detailsPresent=${primitiveValue(activity, "details") !in setOf("null", "")}, " +
            "statePresent=${primitiveValue(activity, "state") !in setOf("null", "") }"
    }.getOrDefault("<invalid activity>")

    private fun logPresenceDispatch(eventName: String, jsonString: String) {
        if (eventName != "PRESENCE_UPDATE" && eventName != "SESSIONS_REPLACE") return
        try {
            val data = json.parseToJsonElement(jsonString).jsonObject["d"]
            when (eventName) {
                "PRESENCE_UPDATE" -> {
                    val presence = data?.jsonObject ?: return
                    val activities = runCatching {
                        presence["activities"]?.jsonArray?.joinToString(separator = "; ") {
                            presenceActivitySummary(it)
                        } ?: "null"
                    }.getOrDefault("<invalid activities>")
                    logger.i(
                        "Gateway",
                        "Received PRESENCE_UPDATE data: status=${primitiveValue(presence, "status")}, " +
                            "activities=[$activities]"
                    )
                }
                "SESSIONS_REPLACE" -> {
                    val sessions = data?.jsonArray ?: return
                    logger.i("Gateway", "Received SESSIONS_REPLACE: sessionCount=${sessions.size}")
                    sessions.forEachIndexed { index, element ->
                        runCatching {
                            val session = element.jsonObject
                            val activities = session["activities"]?.jsonArray?.joinToString(separator = "; ") {
                                presenceActivitySummary(it)
                            } ?: "null"
                            logger.i(
                                "Gateway",
                                "SESSIONS_REPLACE[$index]: status=${primitiveValue(session, "status")}, " +
                                    "active=${primitiveValue(session, "active")}, activities=[$activities]"
                            )
                        }.onFailure {
                            logger.w("Gateway", "SESSIONS_REPLACE[$index]: unable to summarize session")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.w("Gateway", "Unable to summarize $eventName: ${e::class.simpleName}")
        }
    }

    open fun Payload.handleDispatch(jsonString: String) {
        logPresenceDispatch(this.t.toString(), jsonString)
        when (this.t.toString()) {
            "READY" -> {
                val ready = decodePayloadData<Ready>(jsonString) ?: return
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl + "/?v=10&encoding=json"
                logger.i("Gateway", "resume_gateway_url updated to $resumeGatewayUrl")
                logger.i("Gateway", "session_id updated to $sessionId")
                connected = true
                connectionReady.complete(Unit)
                return
            }
            "SESSIONS_REPLACE" -> {
                initialSessionSyncReceived.complete(Unit)
            }
            "RESUMED" -> {
                logger.i("Gateway", "Session Resumed")
                connected = true
                connectionReady.complete(Unit)
            }
            else -> {}
        }
    }

    private suspend inline fun handleInvalidSession() {
        logger.i("Gateway", "Handling Invalid Session")
        logger.d("Gateway", "Sending Identify after 150ms")
        delay(150)
        sendIdentify()
    }

    private suspend inline fun handleHello(jsonString: String) {
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        heartbeatInterval = decodePayloadData<Heartbeat>(jsonString)?.heartbeatInterval ?: return
        logger.i("Gateway", "Setting heartbeatInterval= $heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
    }

    protected inline fun <reified T> decodePayloadData(jsonString: String): T? {
        return json.decodeFromString<PayloadData<T>>(jsonString).d
    }

    private suspend fun sendHeartBeat() {
        logger.i("Gateway", "Sending $HEARTBEAT with seq: $sequence")
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) null else sequence,
        )
    }

    private suspend inline fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect"
            )
        )
    }

    private suspend fun sendIdentify() {
        logger.i("Gateway", "Sending $IDENTIFY")
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload(deviceName)
        )
    }

    private suspend fun sendResume() {
        logger.i("Gateway", "Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token
            )
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatAcknowledged = true
        heartbeatJob = launch {
            // Per Discord spec: first heartbeat after interval * jitter
            val jitter = Math.random()
            delay((interval * jitter).toLong())
            while (isActive) {
                if (!heartbeatAcknowledged) {
                    logger.w("Gateway", "No heartbeat ACK received — zombie connection detected, reconnecting")
                    reconnectWebSocket()
                    return@launch
                }
                heartbeatAcknowledged = false
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    override fun isWebSocketConnected(): Boolean {
        return connected && websocket?.isActive == true
    }

    private suspend inline fun <reified T> send(op: OpCode, d: T?): Boolean {
        return sendMutex.withLock {
            val activeSocket = websocket
            if (activeSocket?.isActive != true) {
                logger.w(
                    "Gateway",
                    "Skipping WebSocket send: op=$op, socketPresent=${activeSocket != null}, socketActive=${activeSocket?.isActive}"
                )
                return@withLock false
            }

            val payload = json.encodeToString(
                OutgoingPayload(
                    op = op,
                    d = d,
                )
            )
            val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
            logger.d("Gateway", "WebSocket send started: op=$op, payloadBytes=$payloadBytes")

            // Log the outgoing presence fields without exposing URLs or image references.
            if (op == PRESENCE_UPDATE) {
                runCatching {
                    val data = json.parseToJsonElement(payload).jsonObject["d"]?.jsonObject
                        ?: return@runCatching
                    val activities = data["activities"]?.jsonArray?.joinToString(separator = "; ") { item ->
                        item.jsonObject.entries.joinToString(separator = ", ") { (key, value) ->
                            when (key) {
                                "url" -> "urlPresent=${value.toString() != "null"}"
                                "assets" -> "assetsKeys=${runCatching { value.jsonObject.keys }.getOrDefault(emptySet())}"
                                "metadata" -> {
                                    val metadata = runCatching { value.jsonObject }.getOrNull()
                                    val count = runCatching { metadata?.get("button_urls")?.jsonArray?.size ?: 0 }.getOrDefault(-1)
                                    "metadataKeys=${metadata?.keys ?: emptySet<String>()}, buttonUrlCount=$count"
                                }
                                else -> "$key=$value"
                            }
                        }
                    } ?: "null"
                    logger.i(
                        "Gateway",
                        "Outgoing PRESENCE_UPDATE payload: status=${primitiveValue(data, "status")}, " +
                            "since=${primitiveValue(data, "since")}, afk=${primitiveValue(data, "afk")}, " +
                            "activities=[$activities]"
                    )
                }.onFailure {
                    logger.w("Gateway", "Unable to summarize outgoing PRESENCE_UPDATE: ${it::class.simpleName}")
                }
            }

            try {
                activeSocket.send(Frame.Text(payload))
                logger.i("Gateway", "WebSocket send completed: op=$op, payloadBytes=$payloadBytes")
                true
            } catch (e: Exception) {
                logger.e(
                    "Gateway",
                    "WebSocket send failed: op=$op, error=${e::class.simpleName}, message=${e.message}"
                )
                throw e
            }
        }
    }

    override fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        resetConnectionSignals()
        coroutineContext.cancelChildren()
        resumeGatewayUrl = null
        sessionId = null
        connected = false
        // Non-blocking close — schedule on a coroutine to avoid ANR
        val ws = websocket
        websocket = null
        if (ws != null) {
            // Capture the client being closed and mark it synchronously, so a
            // connect() that happens before this coroutine runs recreates a
            // fresh client instead of reusing (and then losing) this one.
            val closingClient = client
            clientClosed = true
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    ws.close()
                } catch (_: Exception) {
                    // Already closed or failed — safe to ignore
                }
                try {
                    closingClient.close()
                } catch (_: Exception) {
                    // Safe to ignore
                }
                logger.e("Gateway", "Connection to gateway closed")
            }
        }
    }

    override suspend fun sendActivity(presence: Presence) {
        try {
            withTimeout(30.seconds) {
                // Wait until the *current* connection is authenticated. The
                // ready signals are recreated whenever a connection drops or
                // a new attempt starts, so this can no longer be satisfied by
                // a READY that belonged to a dead socket.
                connectionReady.await()

                val sessionSyncReceived = withTimeoutOrNull(5.seconds) {
                    initialSessionSyncReceived.await()
                    true
                } ?: false
                if (sessionSyncReceived) {
                    logger.i("Gateway", "Initial session sync received; delaying first presence by 1 second")
                    delay(1.seconds)
                } else {
                    logger.w("Gateway", "Initial session sync not received within 5 seconds; sending presence anyway")
                }

                logger.i("Gateway", "Sending $PRESENCE_UPDATE")
                logger.d(
                    "Gateway",
                    "Presence summary: status=${presence.status}, since=${presence.since}, " +
                        "activities=${presence.activities?.joinToString(separator = "; ") { activity ->
                            "name=${activity?.name}, type=${activity?.type}, applicationId=${activity?.applicationId}, " +
                                "detailsPresent=${!activity?.details.isNullOrBlank()}, statePresent=${!activity?.state.isNullOrBlank()}"
                        }}"
                )

                // If the socket died between READY and the actual send, do not
                // drop the presence: wait for the next connection to become
                // ready and send it there. The loop is bounded by the 30s
                // timeout above; if no reconnect happens it ends with the
                // timeout log instead of repeated "Skipping WebSocket send".
                while (true) {
                    val sent = try {
                        send(
                            op = PRESENCE_UPDATE,
                            d = presence
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(
                            "Gateway",
                            "PRESENCE_UPDATE send failed (${e::class.simpleName}); will retry when reconnected"
                        )
                        false
                    }
                    if (sent) break
                    delay(500.milliseconds)
                    connectionReady.await()
                }
                logger.d("Gateway", "PRESENCE_UPDATE send() returned")
            }
        } catch (e: TimeoutCancellationException) {
            logger.e("Gateway", "Timed out waiting for gateway connection")
            return
        }
    }

}
