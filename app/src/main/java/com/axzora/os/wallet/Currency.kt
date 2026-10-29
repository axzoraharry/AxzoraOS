package com.axzora.os.wallet

/**
 * Happy Paisa is a single global unit. Each user picks the currency it is
 * *displayed* in — the balance itself is always HP, so two people in different
 * countries hold the same thing and see it in their own money.
 *
 * Rates are the display conversion for 1 HP. They are deliberately static:
 * a live FX feed would make the same balance appear to change value minute to
 * minute, which is wrong for what is a fixed-value point.
 */
data class Currency(
    val code: String,
    val symbol: String,
    val name: String,
    /** How much of this currency one HP represents. */
    val perHp: Double,
)

object Currencies {

    val ALL = listOf(
        Currency("USD", "$", "US Dollar", 12.0),
        Currency("EUR", "€", "Euro", 11.0),
        Currency("GBP", "£", "British Pound", 9.5),
        Currency("INR", "₹", "Indian Rupee", 1000.0),
        Currency("AED", "د.إ", "UAE Dirham", 44.0),
        Currency("SGD", "S$", "Singapore Dollar", 16.0),
        Currency("AUD", "A$", "Australian Dollar", 18.0),
        Currency("CAD", "C$", "Canadian Dollar", 16.5),
        Currency("JPY", "¥", "Japanese Yen", 1800.0),
        Currency("CNY", "¥", "Chinese Yuan", 87.0),
        Currency("BRL", "R$", "Brazilian Real", 65.0),
        Currency("ZAR", "R", "South African Rand", 220.0),
        Currency("NGN", "₦", "Nigerian Naira", 18000.0),
        Currency("KES", "KSh", "Kenyan Shilling", 1550.0),
        Currency("PHP", "₱", "Philippine Peso", 700.0),
        Currency("IDR", "Rp", "Indonesian Rupiah", 195000.0),
        Currency("MXN", "MX$", "Mexican Peso", 240.0),
        Currency("TRY", "₺", "Turkish Lira", 420.0),
    )

    val DEFAULT = ALL.first { it.code == "USD" }

    fun byCode(code: String): Currency =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT

    /** Picks a sensible starting currency from the device's locale. */
    fun forLocale(): Currency {
        val country = runCatching { java.util.Locale.getDefault().country }.getOrNull().orEmpty()
        val code = when (country.uppercase()) {
            "US" -> "USD"; "GB" -> "GBP"; "IN" -> "INR"; "AE" -> "AED"
            "SG" -> "SGD"; "AU" -> "AUD"; "CA" -> "CAD"; "JP" -> "JPY"
            "CN" -> "CNY"; "BR" -> "BRL"; "ZA" -> "ZAR"; "NG" -> "NGN"
            "KE" -> "KES"; "PH" -> "PHP"; "ID" -> "IDR"; "MX" -> "MXN"
            "TR" -> "TRY"
            "DE", "FR", "ES", "IT", "NL", "IE", "PT", "AT", "BE", "FI", "GR" -> "EUR"
            else -> "USD"
        }
        return byCode(code)
    }

    /** "₹1,250" / "$15" — no decimals for currencies where they add nothing. */
    fun format(hp: Double, currency: Currency): String {
        val value = hp * currency.perHp
        val digits = if (value >= 100 || currency.perHp >= 1000) 0 else 2
        return currency.symbol + "%,.${digits}f".format(value)
    }
}
