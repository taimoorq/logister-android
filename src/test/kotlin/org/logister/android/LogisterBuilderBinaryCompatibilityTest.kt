package org.logister.android

import org.junit.Assert.assertNotNull
import org.junit.Test

class LogisterBuilderBinaryCompatibilityTest {
    @Test
    fun offlineQueueRetainsTheZeroPointTwoJvmEntryPoints() {
        val builderClass = LogisterClient.Builder::class.java
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!

        assertNotNull(
            builderClass.getDeclaredMethod(
                "offlineQueue",
                booleanType,
                intType,
                intType,
            ),
        )
        assertNotNull(
            builderClass.getDeclaredMethod(
                "offlineQueue\$default",
                builderClass,
                booleanType,
                intType,
                intType,
                intType,
                Any::class.java,
            ),
        )
    }

    @Test
    fun offlineQueueOffersAnExplicitMaxAgeOverload() {
        val intType = Int::class.javaPrimitiveType!!

        assertNotNull(
            LogisterClient.Builder::class.java.getDeclaredMethod(
                "offlineQueue",
                Boolean::class.javaPrimitiveType!!,
                intType,
                intType,
                intType,
            ),
        )
    }
}
