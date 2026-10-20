package lab.doppio.bloom.agents

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyTextTest {
    @Test
    fun `an explicit opt-out is a hard stop`() {
        assertTrue(ReplyText.isHardStop("Merci de me désinscrire"))
        assertTrue(ReplyText.isHardStop("Please unsubscribe me"))
        assertTrue(ReplyText.isHardStop("Je souhaite ne plus recevoir vos emails"))
        assertTrue(ReplyText.isHardStop("Merci de ne plus m'écrire"))
        assertTrue(ReplyText.isHardStop("Retirez-moi de votre liste"))
    }

    @Test
    fun `the word stop inside an interested reply is not a hard stop`() {
        assertFalse(ReplyText.isHardStop("On travaille non-stop sur l'app, intéressé"))
        assertFalse(ReplyText.isHardStop("Stop, c'est justement notre sujet, on en parle ?"))
    }
}
