package lab.doppio.bloom.app.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import lab.doppio.bloom.app.outreach.UnsubscribeTokens
import lab.doppio.bloom.core.usecase.UnsubscribeContact

/** Public unsubscribe endpoints: the link in the email, and the RFC 8058 one-click POST. */
class UnsubscribeRoutes(private val tokens: UnsubscribeTokens, private val unsubscribeContact: UnsubscribeContact) {
    fun mount(parent: Route) = parent.unsubscribe()

    private fun Route.unsubscribe() {
        suspend fun unsubscribe(token: String): Boolean {
            val contactId = tokens.verify(token) ?: return false
            unsubscribeContact(contactId)
            return true
        }

        // Lien cliqué par une personne : on confirme sans demander de compte ni de motif
        get("/u/{token}") {
            val ok = unsubscribe(call.parameters["token"]!!)
            call.respondText(
                if (ok) ApiMessages.unsubscribeConfirmation() else ApiMessages.UNSUBSCRIBE_INVALID_LINK,
                ContentType.Text.Html,
                if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
            )
        }
        // RFC 8058 : POST en un clic depuis le client mail
        post("/u/{token}") {
            val ok = unsubscribe(call.parameters["token"]!!)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
        }
    }
}
