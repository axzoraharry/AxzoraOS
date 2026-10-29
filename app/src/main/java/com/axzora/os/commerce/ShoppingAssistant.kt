package com.axzora.os.commerce

/**
 * Shopping assistant agent for "our store" — an equivalent of the Claude Code
 * commerce-builder `/scaffold-commerce-agent` output, as a pure, testable core
 * in the Mr Happy ecosystem.
 *
 * Holds a product catalog and answers natural-ish queries (help, list, search,
 * add to cart, cart, checkout) by parsing intent and mutating a [Cart]. No
 * external deps so it is unit-testable; wire it to the bridge/assistant to make
 * it speak.
 */
object ShoppingAssistant {

    data class Product(val id: String, val name: String, val price: Double, val stock: Int, val tags: Set<String>)

    data class CartLine(val product: Product, val qty: Int)

    data class Cart(val lines: List<CartLine> = emptyList()) {
        val total: Double get() = lines.sumOf { it.product.price * it.qty }
        val count: Int get() = lines.sumOf { it.qty }

        fun add(product: Product, qty: Int = 1): Cart {
            val inCart = lines.any { it.product.id == product.id }
            val newLines = if (inCart) {
                lines.map { if (it.product.id == product.id) it.copy(qty = it.qty + qty) else it }
            } else {
                lines + CartLine(product, qty)
            }
            return Cart(newLines)
        }
    }

    /** Simple "store" catalog (the merchant's inventory). */
    val catalog: List<Product> = listOf(
        Product("p1", "Organic Coffee 250g", 12.99, 120, setOf("coffee", "food")),
        Product("p2", "Himalayan Salt Lamp", 29.99, 40, setOf("home", "decor")),
        Product("p3", "Merino Wool Socks", 9.99, 300, setOf("clothing")),
        Product("p4", "Bamboo Toothbrush (4pk)", 7.50, 200, setOf("eco", "home")),
        Product("p5", "Cold Brew Kit", 39.99, 25, setOf("coffee", "kit")),
    )

    fun list(query: String? = null): List<Product> {
        val q = query?.trim()?.lowercase() ?: return catalog
        return catalog.filter { it.name.lowercase().contains(q) || it.tags.any { t -> t.contains(q) } }
    }

    fun find(id: String): Product? = catalog.firstOrNull { it.id == id }

    /** The shop's welcome/help. */
    fun help(): String =
        "Hi! I'm your shopping assistant. Try: \"list\", \"search coffee\", \"add Organic Coffee 2\", \"cart\", \"checkout\"."

    /**
     * Interpret [query] against [cart] and return (reply, updatedCart, order?).
     * Order is non-null on a successful checkout (drained cart).
     */
    data class Reply(val text: String, val cart: Cart, val order: Order? = null)

    data class Order(val lines: List<CartLine>, val total: Double)

    fun respond(query: String, cart: Cart = Cart()): Reply {
        val q = query.trim()
        val lower = q.lowercase()
        return when {
            lower == "help" || lower == "hi" -> Reply(help(), cart)
            lower == "list" -> Reply("In stock: " + catalog.joinToString(" | ") { it.name }, cart)
            lower == "cart" -> {
                val t = if (cart.lines.isEmpty()) "Your cart is empty." else
                    cart.lines.joinToString(" | ") { "${it.product.name} x${it.qty}" } + "  → total \$${"%.2f".format(cart.total)}"
                Reply(t, cart)
            }
            lower == "checkout" -> {
                if (cart.lines.isEmpty()) Reply("Cart is empty — add something first.", cart)
                else {
                    val order = Order(cart.lines, cart.total)
                    Reply("Order placed! Total \$${"%.2f".format(order.total)}. Thank you!", Cart(), order)
                }
            }
            lower.startsWith("search ") -> {
                val hits = list(lower.removePrefix("search "))
                if (hits.isEmpty()) Reply("No matches for \"${lower.removePrefix("search ").trim()}\".", cart)
                else Reply("Matches: " + hits.joinToString(" | ") { it.name }, cart)
            }
            lower.startsWith("add ") -> {
                val rest = lower.removePrefix("add ").trim()
                val toks = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
                val last = toks.lastOrNull()
                val qty = last?.toIntOrNull() ?: 1
                val name = if (last?.toIntOrNull() != null) toks.dropLast(1).joinToString(" ") else rest
                val product = catalog.firstOrNull { it.name.lowercase().contains(name) }
                if (product == null) Reply("I couldn't find \"$name\". Try \"list\".", cart)
                else Reply("Added ${product.name} x$qty.", cart.add(product, qty))
            }
            lower.startsWith("find ") -> {
                val hits = list(lower.removePrefix("find ").trim())
                if (hits.isEmpty()) Reply("No product found.", cart) else Reply("Found: " + hits.first().name, cart)
            }
            else -> Reply(help(), cart)
        }
    }
}
