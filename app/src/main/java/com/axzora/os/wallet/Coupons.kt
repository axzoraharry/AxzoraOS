package com.axzora.os.wallet

/**
 * Mock offer catalog for the Happy Paisa wallet.
 *
 * Brands here are deliberately fictional. Using real merchant names would
 * imply partnerships that do not exist and would misrepresent those
 * companies; swap in real ones only once actual agreements are in place.
 */
data class Coupon(
    val code: String,
    val brand: String,
    val category: String,
    val title: String,
    val detail: String,
    val costHp: Double,
    val worthInr: Int,
    val emoji: String,
)

object Coupons {
    val ALL = listOf(
        Coupon(
            code = "BITEBOX60",
            brand = "BiteBox",
            category = "Food delivery",
            title = "60% off your first order",
            detail = "Max ₹120 off. Valid on orders above ₹199.",
            costHp = 0.15, worthInr = 120, emoji = "🍔",
        ),
        Coupon(
            code = "TIFFINRUN40",
            brand = "TiffinRun",
            category = "Food delivery",
            title = "Flat ₹80 off",
            detail = "On any lunch order placed before 2 PM.",
            costHp = 0.10, worthInr = 80, emoji = "🍱",
        ),
        Coupon(
            code = "ZIPCAB99",
            brand = "ZipCab",
            category = "Rides",
            title = "₹99 off airport rides",
            detail = "One-time use. Airport pickup or drop only.",
            costHp = 0.12, worthInr = 99, emoji = "🚕",
        ),
        Coupon(
            code = "FRESHKART250",
            brand = "FreshKart",
            category = "Groceries",
            title = "₹250 off groceries",
            detail = "Minimum basket ₹999. Excludes dairy.",
            costHp = 0.30, worthInr = 250, emoji = "🛒",
        ),
        Coupon(
            code = "STREAMPLUS1M",
            brand = "StreamPlus",
            category = "Entertainment",
            title = "1 month free premium",
            detail = "New subscribers only. Auto-renews at ₹149/mo.",
            costHp = 0.20, worthInr = 149, emoji = "🎬",
        ),
        Coupon(
            code = "MEDIQUICK15",
            brand = "MediQuick",
            category = "Pharmacy",
            title = "15% off medicines",
            detail = "Max ₹200 off on prescription orders.",
            costHp = 0.18, worthInr = 200, emoji = "💊",
        ),
        Coupon(
            code = "RECHARGE50",
            brand = "TopUpNow",
            category = "Recharge",
            title = "₹50 cashback on recharge",
            detail = "Credited as Happy Paisa within 24 hours.",
            costHp = 0.05, worthInr = 50, emoji = "📱",
        ),
        Coupon(
            code = "STYLEHUB500",
            brand = "StyleHub",
            category = "Fashion",
            title = "₹500 off fashion",
            detail = "On orders above ₹1,999. Selected brands.",
            costHp = 0.55, worthInr = 500, emoji = "👕",
        ),
    )

    val categories: List<String> = listOf("All") + ALL.map { it.category }.distinct()
}
