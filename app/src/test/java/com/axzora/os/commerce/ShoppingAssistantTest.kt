package com.axzora.os.commerce

import com.axzora.os.commerce.ShoppingAssistant
import com.axzora.os.commerce.ShoppingAssistant.Cart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the shopping assistant agent. */
class ShoppingAssistantTest {

    @Test
    fun helpAndListWork() {
        assertTrue(ShoppingAssistant.respond("help").text.contains("shopping assistant"))
        assertTrue(ShoppingAssistant.respond("list").text.contains("Organic Coffee"))
    }

    @Test
    fun searchFiltersByTagAndName() {
        val coffee = ShoppingAssistant.respond("search coffee")
        assertTrue(coffee.text.contains("Organic Coffee"))
        assertTrue(coffee.text.contains("Cold Brew Kit"))
        val none = ShoppingAssistant.respond("search spaceship")
        assertTrue(none.text.contains("No matches"))
    }

    @Test
    fun addToCartAccumulatesTotal() {
        var cart = Cart()
        cart = ShoppingAssistant.respond("add Organic Coffee 2", cart).cart
        cart = ShoppingAssistant.respond("add Organic Coffee 1", cart).cart
        assertEquals(3, cart.count)
        assertEquals(12.99 * 3, cart.total, 0.0001)
    }

    @Test
    fun checkoutEmitsOrderAndClearsCart() {
        var cart = Cart().add(ShoppingAssistant.find("p2")!!, 2)
        val reply = ShoppingAssistant.respond("checkout", cart)
        assertNotNull(reply.order)
        assertEquals(29.99 * 2, reply.order!!.total, 0.0001)
        assertTrue(reply.cart.lines.isEmpty())
        assertTrue(reply.text.contains("Order placed"))
    }

    @Test
    fun unknownQueryFallsBackToHelp() {
        assertEquals(ShoppingAssistant.respond("xyzzy").text, ShoppingAssistant.help())
    }

    @Test
    fun addUnknownProductIsFriendly() {
        val r = ShoppingAssistant.respond("add hologram")
        assertTrue(r.text.contains("couldn't find"))
    }
}
