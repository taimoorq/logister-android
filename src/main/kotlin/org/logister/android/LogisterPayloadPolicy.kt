package org.logister.android

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Final synchronous hook. Return null to discard the event. Never perform network or disk I/O. */
public fun interface LogisterBeforeSend {
    public fun process(envelope: JSONObject): JSONObject?
}

/** Deterministic payload privacy and size limits applied before persistence or transport. */
public class LogisterPayloadPolicy private constructor(builder: Builder) {
    internal val maxDepth: Int = builder.maxDepth
    internal val maxCollectionItems: Int = builder.maxCollectionItems
    internal val maxStringCharacters: Int = builder.maxStringCharacters
    internal val maxEnvelopeBytes: Int = builder.maxEnvelopeBytes
    internal val sensitiveKeys: Set<String> = builder.sensitiveKeys.map(::normalizeKey).toSet()

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

        public fun sensitiveKeys(keys: Set<String>): Builder = apply {
            sensitiveKeys.clear()
            sensitiveKeys.addAll(keys.filter(String::isNotBlank))
        }

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

internal fun sanitizeEnvelope(
    envelope: JSONObject,
    policy: LogisterPayloadPolicy,
): JSONObject? {
    val budget = ItemBudget(policy.maxCollectionItems)
    val sanitized = sanitizeObject(envelope, policy, budget, depth = 0)
    return sanitized.takeIf {
        it.toString().toByteArray(Charsets.UTF_8).size <= policy.maxEnvelopeBytes
    }
}

private fun sanitizeObject(
    source: JSONObject,
    policy: LogisterPayloadPolicy,
    budget: ItemBudget,
    depth: Int,
): JSONObject {
    if (depth >= policy.maxDepth) return JSONObject().put("_truncated", true)
    val output = JSONObject()
    val keys = source.keys().asSequence().toList().sorted()
    for (key in keys) {
        if (!budget.take()) break
        if (normalizeKey(key) in policy.sensitiveKeys) continue
        val sanitized = sanitizeValue(source.opt(key), policy, budget, depth + 1)
        if (sanitized != null) output.put(key, sanitized)
    }
    return output
}

private fun sanitizeArray(
    source: JSONArray,
    policy: LogisterPayloadPolicy,
    budget: ItemBudget,
    depth: Int,
): JSONArray {
    if (depth >= policy.maxDepth) return JSONArray().put("[TRUNCATED]")
    val output = JSONArray()
    for (index in 0 until source.length()) {
        if (!budget.take()) break
        sanitizeValue(source.opt(index), policy, budget, depth + 1)?.let(output::put)
    }
    return output
}

private fun sanitizeValue(
    value: Any?,
    policy: LogisterPayloadPolicy,
    budget: ItemBudget,
    depth: Int,
): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> sanitizeObject(value, policy, budget, depth)
    is JSONArray -> sanitizeArray(value, policy, budget, depth)
    is Map<*, *> -> sanitizeObject(JSONObject(value), policy, budget, depth)
    is Iterable<*> -> sanitizeArray(JSONArray(value.toList()), policy, budget, depth)
    is String -> sanitizeString(value, policy.maxStringCharacters)
    is Number, is Boolean -> value
    else -> sanitizeString(value.toString(), policy.maxStringCharacters)
}

private fun sanitizeString(value: String, maxCharacters: Int): String {
    val scrubbedBearer = value.replace(Regex("(?i)bearer\\s+[^\\s]+"), "Bearer [REDACTED]")
    val scrubbedUrl = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        .replace(scrubbedBearer) { match -> scrubUrl(match.value) }
    return if (scrubbedUrl.length <= maxCharacters) scrubbedUrl else scrubbedUrl.take(maxCharacters) + "…"
}

private fun scrubUrl(value: String): String {
    if (!value.startsWith("http://") && !value.startsWith("https://")) return value
    return try {
        val uri = URI(value)
        URI(uri.scheme, uri.authority, uri.path, null, null).toString()
    } catch (_: Exception) {
        value.replace(Regex("([?&][^=\\s]+)=([^&\\s]+)"), "$1=[REDACTED]")
    }
}

private fun normalizeKey(value: String): String = value
    .lowercase(Locale.US)
    .replace(Regex("[^a-z0-9]+"), "_")
    .trim('_')

private class ItemBudget(private var remaining: Int) {
    fun take(): Boolean {
        if (remaining <= 0) return false
        remaining -= 1
        return true
    }
}

private val DEFAULT_SENSITIVE_KEYS: Set<String> = setOf(
    "password",
    "passwd",
    "secret",
    "authorization",
    "cookie",
    "set_cookie",
    "access_token",
    "auth_token",
    "token",
    "refresh_token",
    "api_key",
    "client_secret",
    "credential",
    "credentials",
    "mobile_ingest_token",
)
