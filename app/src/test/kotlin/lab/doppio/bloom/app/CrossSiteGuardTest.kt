package lab.doppio.bloom.app

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import lab.doppio.bloom.app.web.CrossSiteGuard
import kotlin.test.Test
import kotlin.test.assertEquals

class CrossSiteGuardTest {
    private fun guarded(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(calls: () -> Int) -> Unit) = testApplication {
        var handled = 0
        routing {
            route("/api") {
                install(CrossSiteGuard)
                post("/drafts/1/approve") { handled++; call.respondText("ok") }
                get("/overview") { call.respondText("ok") }
            }
        }
        block { handled }
    }

    @Test
    fun `cross-site posts are refused before the handler runs`() = guarded { calls ->
        listOf("cross-site", "same-site").forEach { site ->
            val response = client.post("/api/drafts/1/approve") { header("Sec-Fetch-Site", site) }
            assertEquals(HttpStatusCode.Forbidden, response.status, site)
        }
        assertEquals(0, calls())
    }

    @Test
    fun `dashboard and command line posts go through`() = guarded { calls ->
        assertEquals(HttpStatusCode.OK, client.post("/api/drafts/1/approve") { header("Sec-Fetch-Site", "same-origin") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/drafts/1/approve").status)
        assertEquals(2, calls())
    }

    @Test
    fun `reads are never blocked`() = guarded {
        assertEquals(HttpStatusCode.OK, client.get("/api/overview") { header("Sec-Fetch-Site", "cross-site") }.status)
    }
}
