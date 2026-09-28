package cc.moon.internet

import cc.moon.internet.core.Protocol
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.data.orderServers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The star used to change but the row stayed put. These say where a favourite belongs in every
 * sort the chips offer, so the next person to touch the comparators finds out here.
 */
class ServerOrderTest {

    private fun server(name: String) =
        ServerProfile(protocol = Protocol.VLESS, name = name, raw = "vless://$name", address = "$name.example", port = 443)

    private val a = server("alpha")
    private val b = server("bravo")
    private val c = server("charlie")
    private val all = listOf(a, b, c)

    private fun names(list: List<ServerProfile>) = list.map { it.label }

    @Test
    fun aFavouriteFloatsUpInEverySort() {
        val fav = listOf(c.key)
        val pings = mapOf(a.pingKey to 10, b.pingKey to 20, c.pingKey to 300)
        for (sort in listOf("", "default", "ping", "name")) {
            assertEquals("sort=$sort", "charlie", orderServers(all, pings, fav, sort, "", null).first().label)
        }
    }

    @Test
    fun takingTheStarBackPutsTheRowWhereItWas() {
        assertEquals(names(all), names(orderServers(all, emptyMap(), emptyList(), "", "", null)))
    }

    @Test
    fun theRestKeepTheirOrderBehindTheFavourite() {
        assertEquals(listOf("bravo", "alpha", "charlie"),
                     names(orderServers(all, emptyMap(), listOf(b.key), "", "", null)))
    }

    @Test
    fun theConnectedServerOutranksAFavourite() {
        // Every sort, "Обычная" included. This was only ever checked through the name sort, and the
        // default branch had simply forgotten to ask about the pin — so in the order nearly
        // everybody is actually looking at, the server you were on never moved at all.
        for (sort in listOf("", "default", "ping", "name")) {
            assertEquals("sort=$sort", "alpha",
                orderServers(all, emptyMap(), listOf(c.key), sort, "", a.key).first().label)
        }
        // «★ Избранное» too, though it is a filter rather than an order: the row you are connected
        // through is never the one to hide. It goes first, ahead of the starred ones.
        assertEquals(listOf("alpha", "charlie"),
            names(orderServers(all, emptyMap(), listOf(c.key), "favorite", "", a.key)))
    }

    @Test
    fun theConnectedServerSurvivesTheProtocolFilter() {
        // Connect to a VLESS server, tap the Hysteria2 chip, and the server actually carrying your
        // traffic used to vanish from the list — the one row that should never be in question.
        val hy = server("hydra").copy(protocol = Protocol.HYSTERIA2)
        val list = all + hy
        assertEquals(listOf("alpha", "hydra"),
            names(orderServers(list, emptyMap(), emptyList(), "", hy.protocolLabel, a.key)))
        // With nothing connected it filters as plainly as before.
        assertEquals(listOf("hydra"),
            names(orderServers(list, emptyMap(), emptyList(), "", hy.protocolLabel, null)))
    }

    @Test
    fun theFavouriteSortShowsOnlyStarredOnes() {
        assertEquals(listOf("bravo"), names(orderServers(all, emptyMap(), listOf(b.key), "favorite", "", null)))
    }

    /**
     * Nothing starred here means nothing here, not everything here.
     *
     * It used to fall back to the whole list, which is right when nothing anywhere is starred and
     * quite wrong for one subscription among several: that one showed all of its servers while the
     * others showed their stars. The fallback belongs to the whole collection and lives in
     * Store.order(), which is the only place that can see all of it.
     */
    @Test
    fun theFavouriteSortLeavesASubscriptionWithNoStarsEmpty() {
        assertEquals(emptyList<String>(), names(orderServers(all, emptyMap(), emptyList(), "favorite", "", null)))
    }
}
