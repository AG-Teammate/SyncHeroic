package app.syncheroic.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Endpoint(val host: String, val method: String, val path: String)

@Serializable
data class EndpointConfig(
    val schemaVersion: Int,
    val auth: Endpoint,
    val profile: Endpoint,
    val workouts: Endpoint,
)

object EndpointConfigLoader {
    private val allowedHosts = setOf("api.trainheroic.com", "apis.trainheroic.com")
    private val allowedMethods = setOf("GET", "POST")
    private val json = Json { ignoreUnknownKeys = false }

    fun loadBundled(): EndpointConfig {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("endpoints.json"))
        return validate(json.decodeFromString(EndpointConfig.serializer(), resource.bufferedReader().use { it.readText() }))
    }

    fun parseRemote(payload: String): EndpointConfig = validate(
        json.decodeFromString(EndpointConfig.serializer(), payload),
    )

    private fun validate(config: EndpointConfig): EndpointConfig {
        require(config.schemaVersion == 1) { "Unsupported endpoint schema" }
        listOf(config.auth, config.profile, config.workouts).forEach { endpoint ->
            require(endpoint.host in allowedHosts) { "Endpoint host is not allowed" }
            require(endpoint.method.uppercase() in allowedMethods) { "Endpoint method is not allowed" }
            require(endpoint.path.startsWith('/') && !endpoint.path.startsWith("//") && !endpoint.path.contains("..")) {
                "Endpoint path is invalid"
            }
        }
        return config
    }
}

