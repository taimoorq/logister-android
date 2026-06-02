@file:JvmName("LogisterKotlin")

package org.logister.android

import java.util.concurrent.Future

public fun logisterClient(
    apiKey: String,
    baseUrl: String,
    configure: LogisterClient.Builder.() -> Unit = {}
): LogisterClient =
    LogisterClient.builder(apiKey, baseUrl).apply(configure).build()

public fun logisterClientForEndpoint(
    apiKey: String,
    endpoint: String,
    configure: LogisterClient.Builder.() -> Unit = {}
): LogisterClient =
    LogisterClient.endpointBuilder(apiKey, endpoint).apply(configure).build()

public fun logisterEventOptions(
    configure: LogisterEventOptions.Builder.() -> Unit = {}
): LogisterEventOptions =
    LogisterEventOptions.builder().apply(configure).build()

public fun logisterSpan(
    traceId: String,
    name: String,
    durationMs: Double,
    configure: LogisterSpan.Builder.() -> Unit = {}
): LogisterSpan =
    LogisterSpan.builder(traceId, name, durationMs).apply(configure).build()

public fun LogisterClient.captureExceptionAsync(
    throwable: Throwable,
    configure: LogisterEventOptions.Builder.() -> Unit
): Future<LogisterResponse> =
    captureExceptionAsync(throwable, logisterEventOptions(configure))

public fun LogisterClient.captureMessageAsync(
    message: String,
    configure: LogisterEventOptions.Builder.() -> Unit
): Future<LogisterResponse> =
    captureMessageAsync(message, logisterEventOptions(configure))

public fun LogisterClient.captureMetricAsync(
    name: String,
    value: Number,
    unit: String? = null,
    configure: LogisterEventOptions.Builder.() -> Unit = {}
): Future<LogisterResponse> =
    captureMetricAsync(name, value.toDouble(), unit, logisterEventOptions(configure))

public fun LogisterClient.captureTransactionAsync(
    name: String,
    durationMs: Number,
    configure: LogisterEventOptions.Builder.() -> Unit = {}
): Future<LogisterResponse> =
    captureTransactionAsync(name, durationMs.toDouble(), logisterEventOptions(configure))

public fun LogisterClient.captureSpanAsync(
    span: LogisterSpan,
    configure: LogisterEventOptions.Builder.() -> Unit
): Future<LogisterResponse> =
    captureSpanAsync(span, logisterEventOptions(configure))

public fun LogisterClient.checkInAsync(
    slug: String,
    status: String,
    configure: LogisterEventOptions.Builder.() -> Unit = {}
): Future<LogisterResponse> =
    checkInAsync(slug, status, logisterEventOptions(configure))
