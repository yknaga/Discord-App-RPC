package rpc.gateway.entities
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Identify(
    @SerialName("capabilities")
    val capabilities: Int,
    @SerialName("compress")
    val compress: Boolean,
    @SerialName("largeThreshold")
    val largeThreshold: Int,
    @SerialName("properties")
    val properties: Properties,
    @SerialName("token")
    val token: String
){
    companion object {
        /**
         * Identify as the official Android app (os=Android,
         * browser="Discord Android") so Discord classifies this session as
         * mobile: other users see the phone status icon instead of the
         * desktop one, and the session shows up as an Android device.
         * Previously this claimed os=Windows / browser="Discord Client",
         * which made the session look like the desktop app.
         *
         * [deviceName] is the device model reported to Discord; the
         * Android call sites pass Build.MODEL. The gateway module is pure
         * JVM, so it cannot read Build itself.
         */
        fun String.toIdentifyPayload(deviceName: String = "Android") = Identify(
            capabilities = 65,
            compress = false,
            largeThreshold = 100,
            properties = Properties(
                browser = "Discord Android",
                device = deviceName,
                os = "Android"
            ),
            token = this
        )
    }
}

@Serializable
data class Properties(
    @SerialName("browser")
    val browser: String,
    @SerialName("device")
    val device: String,
    @SerialName("os")
    val os: String
)