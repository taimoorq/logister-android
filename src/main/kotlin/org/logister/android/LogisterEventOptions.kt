package org.logister.android

import java.util.Collections
import java.util.LinkedHashMap

/** Optional fields that Logister understands on event payloads. */
public class LogisterEventOptions private constructor(builder: Builder) {
    public val level: String? = builder.level
    public val fingerprint: String? = builder.fingerprint
    public val occurredAt: String? = builder.occurredAt
    public val environment: String? = builder.environment
    public val release: String? = builder.release
    public val traceId: String? = builder.traceId
    public val requestId: String? = builder.requestId
    public val sessionId: String? = builder.sessionId
    public val userId: String? = builder.userId
    public val transactionName: String? = builder.transactionName
    public val durationMs: Double? = builder.durationMs
    public val mechanism: String? = builder.mechanism
    public val handled: Boolean? = builder.handled
    public val inForeground: Boolean? = builder.inForeground
    public val screenName: String? = builder.screenName
    public val context: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(builder.context))

    public class Builder internal constructor() {
        internal var level: String? = null
        internal var fingerprint: String? = null
        internal var occurredAt: String? = null
        internal var environment: String? = null
        internal var release: String? = null
        internal var traceId: String? = null
        internal var requestId: String? = null
        internal var sessionId: String? = null
        internal var userId: String? = null
        internal var transactionName: String? = null
        internal var durationMs: Double? = null
        internal var mechanism: String? = null
        internal var handled: Boolean? = null
        internal var inForeground: Boolean? = null
        internal var screenName: String? = null
        internal val context: MutableMap<String, Any> = LinkedHashMap()

        public fun level(level: String?): Builder = apply {
            this.level = level
        }

        public fun fingerprint(fingerprint: String?): Builder = apply {
            this.fingerprint = fingerprint
        }

        public fun occurredAt(occurredAt: String?): Builder = apply {
            this.occurredAt = occurredAt
        }

        public fun environment(environment: String?): Builder = apply {
            this.environment = environment
        }

        public fun release(release: String?): Builder = apply {
            this.release = release
        }

        public fun traceId(traceId: String?): Builder = apply {
            this.traceId = traceId
        }

        public fun requestId(requestId: String?): Builder = apply {
            this.requestId = requestId
        }

        public fun sessionId(sessionId: String?): Builder = apply {
            this.sessionId = sessionId
        }

        public fun userId(userId: String?): Builder = apply {
            this.userId = userId
        }

        public fun transactionName(transactionName: String?): Builder = apply {
            this.transactionName = transactionName
        }

        public fun durationMs(durationMs: Double): Builder = apply {
            this.durationMs = durationMs
        }

        /** Failure mechanism such as handled_exception, unhandled_exception, anr, or native_crash. */
        public fun mechanism(mechanism: String?): Builder = apply {
            this.mechanism = mechanism
        }

        public fun handled(handled: Boolean): Builder = apply {
            this.handled = handled
        }

        public fun inForeground(inForeground: Boolean): Builder = apply {
            this.inForeground = inForeground
        }

        public fun screenName(screenName: String?): Builder = apply {
            this.screenName = screenName
        }

        public fun context(key: String?, value: Any?): Builder = apply {
            if (!key.isNullOrEmpty() && value != null) {
                context[key] = value
            }
        }

        public fun context(context: Map<String, *>?): Builder = apply {
            context?.forEach { (key, value) ->
                context(key, value)
            }
        }

        public fun build(): LogisterEventOptions = LogisterEventOptions(this)
    }

    public companion object {
        internal val EMPTY: LogisterEventOptions = Builder().build()

        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
