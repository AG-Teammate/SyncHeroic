package app.syncheroic.network

import app.syncheroic.BuildConfig
import app.syncheroic.health.ExerciseMapLoader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class RemoteConfiguration(val endpoints: EndpointConfig, val exerciseMap: Map<String, Int>)

class RemoteConfigUpdater(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    suspend fun fetch(): RemoteConfiguration = withContext(Dispatchers.IO) {
        require(BuildConfig.CONFIG_BASE_URL == "https://raw.githubusercontent.com/AG-Teammate/SyncHeroic/main/config/")
        val endpoints = EndpointConfigLoader.parseRemote(download("endpoints.json"))
        val exerciseMap = ExerciseMapLoader.parse(download("exercise-map.json"))
        RemoteConfiguration(endpoints, exerciseMap)
    }

    private fun download(name: String): String {
        val request = Request.Builder().url(BuildConfig.CONFIG_BASE_URL + name).get().build()
        return http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Remote configuration was unavailable" }
            val body = response.body
            require(body.contentLength() in -1..MAX_BYTES) { "Remote configuration was too large" }
            body.string().also { require(it.encodeToByteArray().size <= MAX_BYTES) { "Remote configuration was too large" } }
        }
    }

    private companion object { const val MAX_BYTES = 64 * 1024L }
}
