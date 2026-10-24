package org.logister.android

import java.net.URLDecoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Final synchronous hook. Return null to discard the event. Never perform network or disk I/O. */
public fun interface LogisterBeforeSend {
    public fun process(envelope: JSONObject): JSONObject?
}

/**
 * Deterministic payload privacy and size limits applied before persistence or transport.
 *
 * A key is sensitive when, after lowercasing and removing every non-alphanumeric character, it
 * equals a configured key or contains one of the built-in fragments (`token`, `secret`,
 * `password`, `passwd`, `apikey`, `authorization`, `credential`, `cookie`). Sensitive values become
 * `[REDACTED]`.
 */
public class LogisterPayloadPolicy private constructor(builder: Builder) {
    internal val maxDepth: Int = builder.maxDepth
    internal val maxCollectionItems: Int = builder.maxCollectionItems
    internal val maxStringCharacters: Int = builder.maxStringCharacters
    internal val maxEnvelopeBytes: Int = builder.maxEnvelopeBytes
    internal val sensitiveKeys: Set<String> = builder.sensitiveKeys.map(::normalizeKey).filter(String::isNotEmpty).toSet()

    internal fun isSensitiveKey(key: String): Boolean {
        val normalized = normalizeKey(key)
        if (normalized.isEmpty()) return false
        return normalized in sensitiveKeys || SENSITIVE_KEY_FRAGMENTS.any(normalized::contains)
    }

    public class Builder internal constructor() {
        internal var maxDepth: Int = 12
        internal var maxCollectionItems: Int = 1_000
        internal var maxStringCharacters: Int = 4_096
        internal var maxEnvelopeBytes: Int = 256 * 1_024
        internal val sensitiveKeys: MutableSet<String> = DEFAULT_SENSITIVE_KEYS.toMutableSet()

        public fun limits(
            maxDepth: Int,
            maxCollectionItems: Int,
            maxStringCharacters: Int,
            maxEnvelopeBytes: Int,
        ): Builder = apply {
            require(maxDepth in 2..32) { "maxDepth must be between 2 and 32" }
            require(maxCollectionItems in 10..10_000) { "maxCollectionItems must be between 10 and 10000" }
            require(maxStringCharacters in 128..32_768) { "maxStringCharacters must be between 128 and 32768" }
            require(maxEnvelopeBytes in 16 * 1_024..1_024 * 1_024) { "maxEnvelopeBytes must be between 16 KiB and 1 MiB" }
            this.maxDepth = maxDepth
            this.maxCollectionItems = maxCollectionItems
            this.maxStringCharacters = maxStringCharacters
            this.maxEnvelopeBytes = maxEnvelopeBytes
        }

        /**
         * Replaces the exact-match key list. The built-in contains-matching fragments always
         * apply, so this cannot disable redaction of keys such as `accessToken`.
         */
        public fun sensitiveKeys(keys: Set<String>): Builder = apply {
            sensitiveKeys.clear()
            sensitiveKeys.addAll(keys.filter(String::isNotBlank))
        }

        /** Adds an exact-match key. It is normalized like the built-in list. */
        public fun addSensitiveKey(key: String): Builder = apply {
            if (key.isNotBlank()) sensitiveKeys.add(key)
        }

        public fun build(): LogisterPayloadPolicy = LogisterPayloadPolicy(this)
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()

        @JvmField
        public val DEFAULT: LogisterPayloadPolicy = Builder().build()
    }
}

/**
 * Sanitizes an envelope without ever dropping its identity fields. Envelope and event keys are
 * exempt from the item budget; every value below them (including the whole `context`) has its own
 * budget, and each top-level context entry has its own nested budget. When an envelope is still too
 * large, the item and string limits are halved, and finally only the required fields are kept.
 * Any truncation is recorded as `context._truncated`.
 */
internal fun sanitizeEnvelope(
    envelope: JSONObject,
    policy: LogisterPayloadPolicy,
): JSONObject? {
    var limits = SanitizeLimits(policy.maxCollectionItems, policy.maxStringCharacters)
    repeat(DEGRADE_PASSES) {
        val sanitized = sanitizeWithLimits(envelope, policy, limits)
        if (sanitized.fitsWithin(policy.maxEnvelopeBytes)) return sanitized
        limits = limits.halved()
    }
    return minimalEnvelope(envelope, policy)?.takeIf { it.fitsWithin(policy.maxEnvelopeBytes) }
}

private class SanitizeLimits(val items: Int, val stringCharacters: Int) {
    fun halved(): SanitizeLimits = SanitizeLimits(
        items = (items / 2).coerceAtLeast(MIN_DEGRADED_ITEMS),
        stringCharacters = (stringCharacters / 2).coerceAtLeast(MIN_DEGRADED_STRING_CHARACTERS),
    )
}

private class SanitizeState(
    val policy: LogisterPayloadPolicy,
    val limits: SanitizeLimits,
) {
    var truncated: Boolean = false
}

private fun sanitizeWithLimits(
    envelope: JSONObject,
    policy: LogisterPayloadPolicy,
    limits: SanitizeLimits,
): JSONObject {
    val state = SanitizeState(policy, limits)
    val output = sanitizeEnvelopeFields(envelope, state)
    if (state.truncated) markTruncated(output)
    return output
}

/** Copies envelope or event fields; these never consume an item budget. */
private fun sanitizeEnvelopeFields(source: JSONObject, state: SanitizeState): JSONObject {
    val output = JSONObject()
    for (key in source.keys().asSequence().toList().sorted()) {
        val value = source.opt(key)
        val sanitized = when {
            key == "event" && value is JSONObject -> sanitizeEnvelopeFields(value, state)
            key == "context" && value is JSONObject -> sanitizeContext(value, state)
            key in IDENTITY_KEYS -> sanitizeIdentityValue(value, state)
            state.policy.isSensitiveKey(key) -> REDACTED
            else -> sanitizeValue(value, state, ItemBudget(state.limits.items), depth = 1)
        }
        if (sanitized != null) output.put(key, sanitized)
    }
    return output
}

/** The context has its own key budget; each entry's nested value has its own item budget. */
private fun sanitizeContext(source: JSONObject, state: SanitizeState): JSONObject {
    val output = JSONObject()
    val keys = source.keys().asSequence().toList()
    val ordered = SDK_CONTEXT_KEYS.filter(keys::contains) + keys.filterNot(SDK_CONTEXT_KEYS::contains).sorted()
    val keyBudget = ItemBudget(state.limits.items)
    for (key in ordered) {
        if (key == TRUNCATED_KEY) continue
        if (!keyBudget.take()) {
            state.truncated = true
            break
        }
        val value = source.opt(key)
        val sanitized = when {
            key in IDENTITY_KEYS -> sanitizeIdentityValue(value, state)
            state.policy.isSensitiveKey(key) -> REDACTED
            else -> sanitizeValue(value, state, ItemBudget(state.limits.items), depth = 2)
        }
        if (sanitized != null) output.put(key, sanitized)
    }
    if (source.optBoolean(TRUNCATED_KEY, false)) state.truncated = true
    return output
}

/** SDK identity fields are bounded but never redacted or rewritten. */
private fun sanitizeIdentityValue(value: Any?, state: SanitizeState): Any? = when (value) {
    null, JSONObject.NULL -> null
    is String -> value.take(state.limits.stringCharacters)
    is Number -> value.takeIf(::isFiniteNumber)
    is Boolean -> value
    else -> sanitizeValue(value, state, ItemBudget(state.limits.items), depth = 1)
}

private fun sanitizeObject(
    source: JSONObject,
    state: SanitizeState,
    budget: ItemBudget,
    depth: Int,
): JSONObject {
    if (depth >= state.policy.maxDepth) {
        state.truncated = true
        return JSONObject().put(TRUNCATED_KEY, true)
    }
    val output = JSONObject()
    // A nested cause is visited last so the reported exception keeps its own frames first.
    val keys = source.keys().asSequence().toList().sortedWith(compareBy<String> { it == CAUSE_KEY }.thenBy { it })
    for (key in keys) {
        if (!budget.take()) {
            state.truncated = true
            output.put(TRUNCATED_KEY, true)
            break
        }
        if (state.policy.isSensitiveKey(key)) {
            output.put(key, REDACTED)
            continue
        }
        val sanitized = sanitizeValue(source.opt(key), state, budget, depth + 1)
        if (sanitized != null) output.put(key, sanitized)
    }
    return output
}

private fun sanitizeArray(
    source: JSONArray,
    state: SanitizeState,
    budget: ItemBudget,
    depth: Int,
): JSONArray {
    if (depth >= state.policy.maxDepth) {
        state.truncated = true
        return JSONArray().put(TRUNCATED_MARKER)
    }
    val output = JSONArray()
    for (index in 0 until source.length()) {
        if (!budget.take()) {
            state.truncated = true
            output.put(TRUNCATED_MARKER)
            break
        }
        sanitizeValue(source.opt(index), state, budget, depth + 1)?.let(output::put)
    }
    return output
}

private fun sanitizeValue(
    value: Any?,
    state: SanitizeState,
    budget: ItemBudget,
    depth: Int,
): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> sanitizeObject(value, state, budget, depth)
    is JSONArray -> sanitizeArray(value, state, budget, depth)
    is Map<*, *> -> sanitizeObject(JSONObject(value), state, budget, depth)
    is Iterable<*> -> sanitizeArray(JSONArray(value.toList()), state, budget, depth)
    is String -> sanitizeString(value, state)
    is Number -> value.takeIf(::isFiniteNumber)
    is Boolean -> value
    else -> sanitizeString(value.toString(), state)
}

/** JSON cannot carry NaN or infinity; org.json throws on them, so they are dropped. */
private fun isFiniteNumber(value: Number): Boolean = when (value) {
    is Double -> value.isFinite()
    is Float -> value.isFinite()
    else -> true
}

private fun sanitizeString(value: String, state: SanitizeState): String {
    val scrubbed = scrubSensitiveText(value, state.policy)
    val maxCharacters = state.limits.stringCharacters
    if (scrubbed.length <= maxCharacters) return scrubbed
    state.truncated = true
    return scrubbed.take(maxCharacters) + "…"
}

/** Applies URL query redaction and credential value patterns to free text. */
internal fun scrubSensitiveText(value: String, policy: LogisterPayloadPolicy): String {
    if (value.isEmpty()) return value
    val withUrls = if (value.contains("://")) URL_PATTERN.replace(value) { scrubUrl(it.value, policy) } else value
    return withUrls
        .replace(BEARER_PATTERN, "Bearer $REDACTED")
        .replace(BASIC_PATTERN) { match ->
            if (looksLikeCredential(match.groupValues[3])) "${match.groupValues[1]}${match.groupValues[2]}$REDACTED" else match.value
        }
        .replace(JWT_PATTERN, REDACTED)
        .replace(LOGISTER_KEY_PATTERN, REDACTED)
}

/** Keeps scheme, host, and path; redacts userinfo and sensitive query values; drops the fragment. */
private fun scrubUrl(value: String, policy: LogisterPayloadPolicy): String {
    val withoutFragment = value.substringBefore('#')
    val queryIndex = withoutFragment.indexOf('?')
    val prefix = (if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment)
        .replace(USERINFO_PATTERN, "$1$REDACTED@")
    if (queryIndex < 0) return prefix
    val query = withoutFragment.substring(queryIndex + 1)
    val redactedQuery = query.split('&').joinToString("&") { parameter ->
        val name = parameter.substringBefore('=')
        if (parameter.contains('=') && policy.isSensitiveKey(decodeQueryComponent(name))) "$name=$REDACTED" else parameter
    }
    return "$prefix?$redactedQuery"
}

private fun decodeQueryComponent(value: String): String = try {
    URLDecoder.decode(value, "UTF-8")
} catch (_: Exception) {
    value
}

// "Basic setup" in prose should survive; real credentials are long and mix case, digits or
// base64 punctuation.
private fun looksLikeCredential(value: String): Boolean {
    if (value.length < 8) return false
    return value.any(Char::isDigit) ||
        value.any { it in "+/=_.~-" } ||
        (value.any(Char::isLowerCase) && value.drop(1).any(Char::isUpperCase))
}

private fun minimalEnvelope(envelope: JSONObject, policy: LogisterPayloadPolicy): JSONObject? {
    val event = envelope.optJSONObject("event") ?: return null
    val state = SanitizeState(policy, SanitizeLimits(MIN_DEGRADED_ITEMS, MIN_DEGRADED_STRING_CHARACTERS))
    val minimal = JSONObject()
    for (key in MINIMAL_EVENT_KEYS) {
        val value = event.opt(key) ?: continue
        val sanitized = when (key) {
            in IDENTITY_KEYS -> sanitizeIdentityValue(value, state)
            else -> sanitizeValue(value, state, ItemBudget(MIN_DEGRADED_ITEMS), depth = 1)
        }
        if (sanitized != null) minimal.put(key, sanitized)
    }
    val context = JSONObject()
    event.optJSONObject("context")?.let { source ->
        for (key in MINIMAL_CONTEXT_KEYS) {
            val value = source.opt(key) ?: continue
            sanitizeValue(value, state, ItemBudget(MIN_DEGRADED_ITEMS), depth = 2)?.let { context.put(key, it) }
        }
    }
    minimal.put("context", context.put(TRUNCATED_KEY, true))
    return JSONObject().put("event", minimal)
}

private fun markTruncated(envelope: JSONObject) {
    val event = envelope.optJSONObject("event") ?: envelope
    val context = event.optJSONObject("context")
    if (context != null) context.put(TRUNCATED_KEY, true) else event.put(TRUNCATED_KEY, true)
}

private fun JSONObject.fitsWithin(maxBytes: Int): Boolean =
    toString().toByteArray(Charsets.UTF_8).size <= maxBytes

internal fun normalizeKey(value: String): String = buildString(value.length) {
    for (character in value.lowercase(Locale.US)) {
        if (character in 'a'..'z' || character in '0'..'9') append(character)
    }
}

private class ItemBudget(private var remaining: Int) {
    fun take(): Boolean {
        if (remaining <= 0) return false
        remaining -= 1
        return true
    }
}

private const val REDACTED = "[REDACTED]"
private const val TRUNCATED_KEY = "_truncated"
private const val TRUNCATED_MARKER = "[TRUNCATED]"
private const val CAUSE_KEY = "cause"
private const val DEGRADE_PASSES = 4
private const val MIN_DEGRADED_ITEMS = 10
private const val MIN_DEGRADED_STRING_CHARACTERS = 128

private val URL_PATTERN = Regex("(?i)\\bhttps?://[^\\s\"'<>]+")
private val USERINFO_PATTERN = Regex("(?i)^([a-z][a-z0-9+.-]*://)[^/?#@]*@")
private val BEARER_PATTERN = Regex("(?i)\\bbearer\\s+[^\\s\"',;]+")
private val BASIC_PATTERN = Regex("(?i)\\b(basic)(\\s+)([A-Za-z0-9._~+/-]+=*)")
private val JWT_PATTERN = Regex("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
private val LOGISTER_KEY_PATTERN = Regex("\\blgst_[A-Za-z0-9_-]+")

/** The SDK's own correlation and identity fields; never redacted. */
private val IDENTITY_KEYS: Set<String> = setOf(
    "uuid", "event_id", "event_type", "occurred_at", "trace_id", "span_id", "parent_span_id",
    "request_id", "session_id",
)

/** SDK-owned context entries are kept ahead of caller keys when the context key budget runs out. */
private val SDK_CONTEXT_KEYS: List<String> = listOf(
    "platform", "telemetry_schema_version", "error", "exception", "exception_class", "capture_source",
    "exception_data_policy", "error_mechanism", "handled", "diagnostic", "diagnostic_external_id",
    "build_provenance", "app", "session", "installation", "sdk", "device", "os", "breadcrumbs",
    "service", "package_name", "app_version", "build_number", "build_type", "environment", "release",
    "trace_id", "span_id", "parent_span_id", "request_id", "session_id", "user_id", "transaction_name",
    "duration_ms", "screen_name", "in_foreground", "installation_id_hash", "process_name",
)

private val MINIMAL_EVENT_KEYS: List<String> = listOf(
    "uuid", "event_type", "message", "level", "occurred_at", "fingerprint", "environment", "release",
    "trace_id", "request_id", "session_id", "user_id", "transaction_name", "duration_ms", "evidence",
)

private val MINIMAL_CONTEXT_KEYS: List<String> = listOf(
    "platform", "telemetry_schema_version", "error", "capture_source", "exception_data_policy", "sdk",
)

private val SENSITIVE_KEY_FRAGMENTS: List<String> = listOf(
    "token", "secret", "password", "passwd", "apikey", "authorization", "credential", "cookie",
)

// Shared default redaction spec: keep identical across the Logister SDKs.
private val DEFAULT_SENSITIVE_KEYS: Set<String> = setOf(
    "password", "passwd", "pwd", "secret", "token", "apikey", "authorization", "proxyauthorization",
    "cookie", "setcookie", "sessiontoken", "sessionkey", "credential", "credentials", "privatekey",
    "clientsecret", "accesstoken", "refreshtoken", "idtoken", "authtoken", "xapikey", "xauthtoken",
    "xaccesstoken", "xcsrftoken", "xxsrftoken", "signature", "ssn", "creditcard", "cardnumber", "cvv",
)
