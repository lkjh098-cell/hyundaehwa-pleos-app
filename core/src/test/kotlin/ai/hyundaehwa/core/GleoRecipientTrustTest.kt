package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class GleoRecipientTrustTest {
    private val digest = "a1".repeat(32)
    @Test fun absentOrMalformedOemConfigurationNeverEnablesDelivery() {
        for ((name, pin) in listOf("" to "", "ai.example.gleo" to "", "ai.example.gleo" to "abc",
            "ai.example.gleo/Receiver" to digest, "ai.example.gleo " to digest)) {
            assertFalse(GleoRecipientTrust.configured(name, pin))
            assertFalse(GleoRecipientTrust.matches(name, pin, listOf(digest)))
        }
    }
    @Test fun onlyTheExactConfiguredCurrentSingleSignerCanReceiveContext() {
        assertTrue(GleoRecipientTrust.matches("ai.example.gleo", digest, listOf(digest.uppercase())))
        assertFalse(GleoRecipientTrust.matches("ai.example.gleo", digest, emptyList()))
        assertFalse(GleoRecipientTrust.matches("ai.example.gleo", digest, listOf("b2".repeat(32))))
        assertFalse(GleoRecipientTrust.matches("ai.example.gleo", digest, listOf(digest, "b2".repeat(32))))
    }
}
