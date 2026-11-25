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
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Discord Gateway transport.
 *
 * Important: this class implements the Gateway protocol used by the application. It does not
 * provide an official Discord-supported mobile RPC integration.
 */
open class DiscordWebSocketImpl(
    private val token: String,
    private val logger: Logger = NoOpLogger
) : DiscordWebSocket {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"

    @Volatile
    private var websocket: DefaultClientWebSocketSession? = null

    // Gateway sequence is nullable: Heartbeat must send JSON null before the first dispatch.
    @Volatile
    private var sequence: Int? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var heartbeatInterval = 0L

    @Volatile
    private var resumeGatewayUrl: String? = null

    @Volatile
    private var connected = false

    @Volatile
    private var heartbeatAcknowledged = true

    @Volatile
    private var latestPresence: Presence? = null

    @Volatile
    private var lastSentPresence: Presence? = null

    @Volatile
    private var connectionReady = CompletableDeferred<Unit>()

    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    private val connectionMutex = Mutex()
    private val sendMutex = Mutex()
    private val presenceMutex = Mutex()

    private val client: HttpClient = HttpClient {
        install(WebSockets)
    }

    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.IO

    /** Starts one managed connection loop. Repeated calls do not create parallel sockets. */
    override suspend fun connect() {
        connectionMutex.withLock {
            if (connectionJob?.isActive == true) return
            connectionJob = launch { connectionLoop() }
        }
    }

    private suspend fun connectionLoop() {
        var retryDelayMs = 1_000L

        while (currentCoroutineContext().isActive) {
            // Keep an incomplete readiness signal across failed handshakes. A new signal is
            // created in finally only after a previously authenticated socket is lost.
            val attemptReady = connectionReady
            connected = false
            lastSentPresence = null
            heartbeatAcknowledged = true

            var session: DefaultClientWebSocketSession? = null
            try {
                val url = resumeGatewayUrl ?: gatewayUrl
                logger.i("Gateway", "Connecting to Gateway")
                session = client.webSocketSession(url)
                websocket = session

                session.incoming.receiveAsFlow().collect { frame ->
                    if (frame is Frame.Text) {
                        onMessage(frame.readText())
                    }
                }

                val reason = session.closeReason?.await()
                val closeCode = reason?.code?.toInt() ?: -1
                logger.w(
                    "Gateway",
                    "Closed with code: ${reason?.code}, reason: ${reason?.message}"
                )

                if (closeCode == 4006 || closeCode == 4007 || closeCode == 4009) {
                    clearSession("Gateway close code $closeCode")
                }

                if (!isRecoverableCloseCode(closeCode)) {
                    logger.e("Gateway", "Not reconnecting after close code $closeCode")
                    return
                }

                val waitMs = if (closeCode == 4008) 5_000L else retryDelayMs
                delay(waitMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Gateway", "Connection error: ${e.message ?: e::class.simpleName}")
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
            } finally {
                connected = false
                heartbeatJob?.cancel()
                heartbeatJob = null
                if (websocket === session) websocket = null

                // Wake senders so they switch to the next connection generation instead of
                // continuing to regard the previous READY as valid.
                if (connectionReady === attemptReady && attemptReady.isCompleted) {
                    connectionReady = CompletableDeferred()
                }
            }
        }
    }

    /** Close codes for which opening a fresh Gateway connection is appropriate. */
    private fun isRecoverableCloseCode(code: Int): Boolean =
        code == -1 || code in setOf(4000, 4003, 4005, 4006, 4007, 4008, 4009) || code == 1001

    private fun clearSession(reason: String) {
        logger.w("Gateway", "Clearing resumable session: $reason")
        sessionId = null
        sequence = null
        resumeGatewayUrl = null
    }

    private suspend fun onMessage(jsonString: String) {
        val payload = json.decodeFromString<Payload>(jsonString)
        logger.d("Gateway", "Received op:${payload.op}, seq:${payload.s}, event:${payload.t}")

        payload.s?.let { sequence = it }

        when (payload.op) {
            DISPATCH -> {
                payload.handleDispatch(jsonString)
                if (payload.t == "READY" || payload.t == "RESUMED") {
                    sendLatestPresence()
                }
            }
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession(jsonString)
            HELLO -> handleHello(jsonString)
            HEARTBEAT_ACK -> {
                heartbeatAcknowledged = true
                logger.d("Gateway", "Heartbeat ACK received")
            }
            else -> Unit
        }
    }

    open fun Payload.handleDispatch(jsonString: String) {
        when (this.t) {
            "READY" -> {
                val ready = decodePayloadData<Ready>(jsonString) ?: return
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl?.let {
                    "${it.trimEnd('/')}/?v=10&encoding=json"
                }
                connected = true
                connectionReady.complete(Unit)
                logger.i("Gateway", "READY received; Gateway session authenticated")
            }
            "RESUMED" -> {
                connected = true
                connectionReady.complete(Unit)
                logger.i("Gateway", "Session resumed")
            }
        }
    }

    private suspend fun handleInvalidSession(jsonString: String) {
        val resumable = decodePayloadData<Boolean>(jsonString) ?: false
        val canResume = resumable && !sessionId.isNullOrBlank() && sequence != null
        val waitMs = Random.nextLong(1_000L, 5_001L)
        logger.w("Gateway", "INVALID_SESSION (resumable=$resumable); retrying in ${waitMs}ms")
        delay(waitMs)

        if (!canResume) {
            clearSession("INVALID_SESSION is not resumable")
        }
        // INVALID_SESSION requires a fresh Gateway connection. HELLO on that connection
        // will select RESUME (when allowed) or IDENTIFY (when the session was cleared).
        websocket?.close(CloseReason(4000, "INVALID_SESSION; reconnecting"))
    }

    private suspend fun handleHello(jsonString: String) {
        heartbeatInterval = decodePayloadData<Heartbeat>(jsonString)?.heartbeatInterval ?: 0L
        if (heartbeatInterval <= 0L) {
            logger.e("Gateway", "HELLO contained an invalid heartbeat interval")
            reconnectWebSocket()
            return
        }

        startHeartbeatJob(heartbeatInterval)

        if (!sessionId.isNullOrBlank() && sequence != null) {
            sendResume()
        } else {
            sendIdentify()
        }
    }

    protected inline fun <reified T> decodePayloadData(jsonString: String): T? =
        json.decodeFromString<PayloadData<T>>(jsonString).d

    /** The d field must be a JSON integer or JSON null, never the strings "123" / "null". */
    private suspend fun sendHeartBeat() {
        logger.d("Gateway", "Sending HEARTBEAT with seq: $sequence")
        send(op = HEARTBEAT, d = sequence)
    }

    private suspend fun reconnectWebSocket() {
        logger.w("Gateway", "Gateway requested reconnect")
        websocket?.close(CloseReason(4000, "Attempting to reconnect"))
    }

    private suspend fun sendIdentify() {
        logger.i("Gateway", "Sending IDENTIFY")
        send(op = IDENTIFY, d = token.toIdentifyPayload())
    }

    private suspend fun sendResume() {
        val currentSessionId = sessionId
        val currentSequence = sequence
        if (currentSessionId.isNullOrBlank() || currentSequence == null) {
            logger.w("Gateway", "Cannot RESUME without session_id and sequence; sending IDENTIFY")
            clearSession("Resume state incomplete")
            sendIdentify()
            return
        }

        logger.i("Gateway", "Sending RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = currentSequence,
                sessionId = currentSessionId,
                token = token
            )
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatAcknowledged = true
        heartbeatJob = launch {
            // Discord specifies an initial randomized delay to avoid synchronized heartbeats.
            delay((interval * Random.nextDouble()).toLong())
            while (isActive) {
                if (!heartbeatAcknowledged) {
                    logger.w("Gateway", "Heartbeat ACK missing; reconnecting")
                    reconnectWebSocket()
                    return@launch
                }

                heartbeatAcknowledged = false
                if (!send(op = HEARTBEAT, d = sequence)) {
                    logger.w("Gateway", "Heartbeat could not be sent; reconnecting")
                    reconnectWebSocket()
                    return@launch
                }
                delay(interval)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isWebSocketConnected(): Boolean =
        connected && websocket?.isActive == true

    /** Returns false if there is no live socket or the frame could not be written. */
    private suspend fun <T> send(op: OpCode, d: T?): Boolean = sendMutex.withLock {
        val ws = websocket
        if (ws == null || !ws.isActive) {
            logger.w("Gateway", "Dropped outgoing opcode ${op.value}: WebSocket is not active")
            return@withLock false
        }

        try {
            val payload = json.encodeToString(OutgoingPayload(op = op, d = d))
            ws.send(Frame.Text(payload))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Gateway", "Failed to send opcode ${op.value}: ${e.message}")
            false
        }
    }

    private suspend fun awaitGatewayReady() {
        withTimeout(30.seconds) {
            while (currentCoroutineContext().isActive) {
                val ready = connectionReady
                ready.await()
                if (ready === connectionReady && connected && websocket?.isActive == true) {
                    return@withTimeout
                }
            }
        }
    }

    /** Sends only the newest queued Presence, once per authenticated connection. */
    private suspend fun sendLatestPresence() {
        presenceMutex.withLock {
            val presence = latestPresence ?: return
            if (!connected || websocket?.isActive != true || presence == lastSentPresence) return

            if (send(op = PRESENCE_UPDATE, d = presence)) {
                lastSentPresence = presence
                logger.i("Gateway", "Presence frame written to WebSocket")
            } else {
                logger.w("Gateway", "Presence frame was not written; it will be retried after reconnect")
            }
        }
    }

    override suspend fun sendActivity(presence: Presence) {
        latestPresence = presence
        try {
            awaitGatewayReady()
            sendLatestPresence()
        } catch (e: TimeoutCancellationException) {
            logger.e("Gateway", "Timed out waiting for an authenticated Gateway connection")
        }
    }

    override fun close() {
        connected = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        connectionJob?.cancel()
        connectionJob = null
        coroutineContext.cancelChildren()
        connectionReady = CompletableDeferred()
        clearSession("Client closed")
        latestPresence = null
        lastSentPresence = null

        val ws = websocket
        websocket = null
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                ws?.close()
            } catch (_: Exception) {
                // The socket may already be closed.
            }
            try {
                client.close()
            } catch (_: Exception) {
                // Client may already be closed.
            }
            logger.i("Gateway", "Gateway client closed")
        }
    }
}
