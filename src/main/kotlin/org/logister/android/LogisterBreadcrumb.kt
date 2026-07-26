package org.logister.android

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone

/** A bounded, privacy-conscious activity marker attached to later events. */
public class LogisterBreadcrumb private constructor(builder: Builder) {
    public val timestamp: String = builder.timestamp ?: iso8601(System.currentTimeMillis())
    public val category: String = builder.category ?: "app"
    public val level: String = builder.level ?: "info"
    public val message: String? = builder.message
    public val data: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(builder.data))

    internal fun asMap(): Map<String, Any> {
        val value = LinkedHashMap<String, Any>()
        value["timestamp"] = timestamp
        value["category"] = category
        value["level"] = level
        if (!message.isNullOrBlank()) value["message"] = message
        if (data.isNotEmpty()) value["data"] = data
        return value
    }

    public class Builder internal constructor(internal val message: String?) {
        internal var timestamp: String? = null
        internal var category: String? = null
        internal var level: String? = null
        internal val data: MutableMap<String, Any> = LinkedHashMap()

        public fun timestamp(timestamp: String?): Builder = apply { this.timestamp = timestamp }
        public fun category(category: String?): Builder = apply { this.category = category }
        public fun level(level: String?): Builder = apply { this.level = level }
        public fun data(key: String?, value: Any?): Builder = apply {
            if (!key.isNullOrBlank() && value != null && data.size < 20) data[key] = value
        }
        public fun build(): LogisterBreadcrumb = LogisterBreadcrumb(this)
    }

    public companion object {
        @JvmStatic
        public fun builder(message: String?): Builder = Builder(message)
    }
}

private fun iso8601(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(timestamp))

internal class LogisterBreadcrumbBuffer(private val capacity: Int) {
    private val entries: ArrayDeque<LogisterBreadcrumb> = ArrayDeque()

    @Synchronized
    fun add(breadcrumb: LogisterBreadcrumb) {
        if (capacity <= 0) return
        while (entries.size >= capacity) entries.removeFirst()
        entries.addLast(breadcrumb)
    }

    @Synchronized
    fun snapshot(): List<Map<String, Any>> = entries.map { it.asMap() }
}
