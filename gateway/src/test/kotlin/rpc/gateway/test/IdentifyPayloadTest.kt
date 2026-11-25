package rpc.gateway.test

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import rpc.gateway.entities.Identify.Companion.toIdentifyPayload
import rpc.gateway.entities.OutgoingPayload
import rpc.gateway.entities.op.OpCode

class IdentifyPayloadTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `identify properties present the session as the Android app`() {
        val encoded = json.encodeToString(
            OutgoingPayload(
                op = OpCode.IDENTIFY,
                d = "token".toIdentifyPayload(deviceName = "TB320FC")
            )
        )

        assertTrue(encoded.contains(""""os":"Android""""))
        assertTrue(encoded.contains(""""browser":"Discord Android""""))
        assertTrue(encoded.contains(""""device":"TB320FC""""))
    }

    @Test
    fun `identify properties default to a generic Android device`() {
        val encoded = json.encodeToString(
            OutgoingPayload(
                op = OpCode.IDENTIFY,
                d = "token".toIdentifyPayload()
            )
        )

        assertTrue(encoded.contains(""""device":"Android""""))
    }
}
