package rpc.gateway.test

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import rpc.gateway.entities.OutgoingPayload
import rpc.gateway.entities.op.OpCode

class HeartbeatPayloadTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `heartbeat sequence is encoded as a JSON number`() {
        val encoded = json.encodeToString(
            OutgoingPayload(op = OpCode.HEARTBEAT, d = 42 as Int?)
        )

        assertEquals("""{"op":1,"d":42}""", encoded)
    }

    @Test
    fun `heartbeat without sequence is encoded as JSON null`() {
        val encoded = json.encodeToString(
            OutgoingPayload(op = OpCode.HEARTBEAT, d = null as Int?)
        )

        assertEquals("""{"op":1,"d":null}""", encoded)
    }
}
