package lab.doppio.bloom.app.outreach

import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.internet.InternetAddress
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendFailureTest {
    @Test
    fun `an SMTP rejection before acceptance is a refusal`() {
        assertTrue(isRefusedBeforeAcceptance(SMTPSendFailedException("MAIL FROM", 550, "mailbox unavailable", null, null, null, null)))
        assertTrue(isRefusedBeforeAcceptance(SMTPAddressFailedException(InternetAddress("a@b.fr"), "RCPT TO", 550, "no such user")))
        assertTrue(isRefusedBeforeAcceptance(SendFailedException("refused", null, null, null, arrayOf(InternetAddress("a@b.fr")))))
    }

    @Test
    fun `anything else, including a timeout after DATA, is not a refusal`() {
        assertFalse(isRefusedBeforeAcceptance(SocketTimeoutException("read timed out")))
        assertFalse(isRefusedBeforeAcceptance(MessagingException("lost connection")))
        assertFalse(isRefusedBeforeAcceptance(SendFailedException("partial", null, arrayOf(InternetAddress("a@b.fr")), null, null)))
    }
}
