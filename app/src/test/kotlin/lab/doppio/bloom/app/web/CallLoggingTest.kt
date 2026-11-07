package lab.doppio.bloom.app.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.post
import lab.doppio.bloom.app.maskedUri
import lab.doppio.bloom.app.routeTest
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLoggingTest {
    @Test
    fun `the token of an unsubscribe link is masked`() {
        assertEquals("/u/***", maskedUri("/u/42.abcdef"))
        assertEquals("/u/***?x=1", maskedUri("/u/42.abcdef?x=1"))
        assertEquals("/api/overview", maskedUri("/api/overview"))
    }

    @Test
    fun `the unsubscribe token never reaches the call logs`() = routeTest { ports ->
        val contact = ports.contacts.add()
        val token = ports.tokens.tokenFor(contact.id)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        try {
            client.get("/u/$token")
            client.post("/u/$token")
            client.get("/u/not-a-valid-token")
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.any { "/u/***" in it }, "the calls are logged, masked: $lines")
        assertFalse(lines.any { token in it || "not-a-valid-token" in it }, "no token in the logs: $lines")
    }
}
