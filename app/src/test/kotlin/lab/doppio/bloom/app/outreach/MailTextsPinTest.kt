package lab.doppio.bloom.app.outreach

import lab.doppio.bloom.app.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the text appended to every outgoing email and the default legal footers. */
class MailTextsPinTest {
    private val mail = testConfig().mail
    private val gateway = EmailGateway(mail, UnsubscribeTokens("fedcba9876543210"))
    private val url = "${mail.publicBaseUrl}/u/${UnsubscribeTokens("fedcba9876543210").tokenFor(7)}"

    @Test
    fun `the French footer carries the legal line and the one-click link`() {
        assertEquals(
            "\n\n--\n${mail.legalFooterFr}\nPas intéressé ? Un clic et vous ne recevrez plus rien de notre part : $url",
            gateway.footer(7, "fr"),
        )
    }

    @Test
    fun `the English footer carries the legal line and the one-click link`() {
        assertEquals(
            "\n\n--\n${mail.legalFooterEn}\nNot interested? One click and you will not hear from us again: $url",
            gateway.footer(7, "en"),
        )
    }

    @Test
    fun `the default legal footers are the ones of Doppio Lab`() {
        assertEquals(
            "Doppio Lab (Advisely), Paris. Vos coordonnées professionnelles proviennent de sources publiques " +
                "(site de votre entreprise, annuaire des entreprises) et ne servent qu'à ce contact.",
            mail.legalFooterFr,
        )
        assertEquals(
            "Doppio Lab (Advisely), Paris, France. Your business contact details come from public sources and are used only for this contact.",
            mail.legalFooterEn,
        )
        assertEquals("l'équipe Doppio", testConfig().sending.senderName)
    }
}
