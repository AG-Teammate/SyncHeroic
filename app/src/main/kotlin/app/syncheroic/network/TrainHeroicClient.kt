package app.syncheroic.network

import app.syncheroic.data.CredentialVault
import app.syncheroic.data.Credentials
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class TrainHeroicException(message: String, val status: Int? = null) : IOException(message)

data class Profile(val userId: Int)

class TrainHeroicClient(
    endpoints: EndpointConfig,
    private val credentialVault: CredentialVault,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    @Volatile private var endpoints: EndpointConfig = endpoints
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var memorySession: String? = null

    suspend fun testAndSave(email: String, password: String): Profile = withContext(Dispatchers.IO) {
        val session = authenticate(email, password)
        memorySession = session
        credentialVault.save(Credentials(email.trim(), password, session))
        profileWithSession(session)
    }

    suspend fun profile(): Profile = withContext(Dispatchers.IO) {
        profileWithSession(session())
    }

    suspend fun fetchWorkouts(start: LocalDate, end: LocalDate): String = withContext(Dispatchers.IO) {
        require(!end.isBefore(start))
        val windows = buildList {
            var cursor = start
            while (!cursor.isAfter(end)) {
                val windowEnd = minOf(cursor.plusDays(WINDOW_DAYS - 1L), end)
                add(cursor to windowEnd)
                cursor = windowEnd.plusDays(1)
            }
        }
        val deduplicated = linkedMapOf<String, JsonObject>()
        windows.forEach { (windowStart, windowEnd) ->
            val url = endpointUrl(endpoints.workouts).newBuilder()
                .addQueryParameter("startDate", windowStart.toString())
                .addQueryParameter("endDate", windowEnd.toString())
                .build()
            val response = authenticatedGet(url)
            val array = json.parseToJsonElement(response) as? JsonArray
                ?: throw TrainHeroicException("Workout response was not a collection")
            array.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "$windowStart-$index"
                deduplicated[id] = obj
            }
        }
        JsonArray(deduplicated.values.toList()).toString()
    }

    fun signOut() {
        memorySession = null
        credentialVault.clear()
    }

    fun replaceEndpoints(config: EndpointConfig) { endpoints = config }

    private fun profileWithSession(session: String): Profile {
        val body = executeWithRetry(
            Request.Builder().url(endpointUrl(endpoints.profile)).header(SESSION_HEADER, session).get().build(),
        )
        val obj = json.parseToJsonElement(body) as? JsonObject ?: throw TrainHeroicException("Profile response was invalid")
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: throw TrainHeroicException("Profile response had no user ID")
        return Profile(id)
    }

    private fun authenticatedGet(url: HttpUrl): String {
        var active = session()
        var response = execute(Request.Builder().url(url).header(SESSION_HEADER, active).get().build())
        if (response.first == 401 || response.first == 403) {
            memorySession = null
            active = session(forceLogin = true)
            response = execute(Request.Builder().url(url).header(SESSION_HEADER, active).get().build())
        }
        if (response.first !in 200..299) throw TrainHeroicException("TrainHeroic request failed (HTTP ${response.first})", response.first)
        return response.second
    }

    @Synchronized
    private fun session(forceLogin: Boolean = false): String {
        if (!forceLogin) memorySession?.let { return it }
        val credentials = credentialVault.load() ?: throw TrainHeroicException("Sign in is required")
        if (!forceLogin) credentials.sessionToken?.let { memorySession = it; return it }
        return authenticate(credentials.email, credentials.password).also { session ->
            memorySession = session
            credentialVault.save(credentials.copy(sessionToken = session))
        }
    }

    private fun authenticate(email: String, password: String): String {
        val request = Request.Builder()
            .url(endpointUrl(endpoints.auth))
            .post(FormBody.Builder().add("email", email.trim()).add("password", password).build())
            .header("Accept", "application/json")
            .build()
        val (status, body) = execute(request)
        if (status !in 200..299) throw TrainHeroicException("TrainHeroic sign-in failed (HTTP $status)", status)
        val obj = json.parseToJsonElement(body) as? JsonObject ?: throw TrainHeroicException("Sign-in response was invalid")
        return (obj["session_id"] as? JsonPrimitive)?.contentOrNull
            ?: throw TrainHeroicException("Sign-in response contained no session")
    }

    private fun executeWithRetry(request: Request): String {
        repeat(MAX_ATTEMPTS) { attempt ->
            val (status, body) = execute(request)
            if (status in 200..299) return body
            if (status !in 500..599 || attempt == MAX_ATTEMPTS - 1) {
                throw TrainHeroicException("TrainHeroic request failed (HTTP $status)", status)
            }
            Thread.sleep(250L shl attempt)
        }
        error("unreachable")
    }

    private fun execute(request: Request): Pair<Int, String> = http.newCall(request).execute().use { response ->
        response.code to response.body.string()
    }

    private fun endpointUrl(endpoint: Endpoint): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(endpoint.host)
        .addPathSegments(endpoint.path.removePrefix("/"))
        .build()

    private companion object {
        const val SESSION_HEADER = "session-token"
        const val MAX_ATTEMPTS = 4
        const val WINDOW_DAYS = 180L
    }
}
