package com.axzora.os.financial

/**
 * Happy Paisa (HP) market + collateral analytics — the data behind the
 * "Market Overview" and "Collateral Ratio" cards.
 *
 * Pure + dependency-free so it is unit-testable; the app/server feed it the
 * on-chain-ish raw numbers (total supply, holders, locked collateral, price).
 */
object MarketProvider {

    /** A curated, human-readable market snapshot. */
    data class MarketSnapshot(
        val priceUsd: Double,
        val priceInr: Double,
        val priceStable: Boolean,     // peg marked STABLE
        val marketCapUsd: Double,
        val marketCapInr: Double,
        val volume24hUsd: Double,
        val totalSupply: Double,
        val holders: Int,
        val collateralRatio: Double,  // e.g. 1.00 = fully collateralized
        val collateralRatioPct: Int,
    )

    /** Compute collateral ratio: collateral value held vs. circulation. */
    fun collateralRatio(collateralUsd: Double, totalSupply: Double, priceUsd: Double): Double {
        val circ = totalSupply * priceUsd
        return if (circ <= 0.0) 0.0 else collateralUsd / circ
    }

    /** Always convert USD -> INR at the given rate (≈₹83–85/USD, or the peg). */
    fun usdToInr(usd: Double, usdInr: Double): Double = usd * usdInr

    /** Build a market snapshot from raw inputs. */
    fun snapshot(
        totalSupply: Double = 0.0,
        holders: Int = 0,
        collateralUsd: Double = 0.0,
        priceUsd: Double = 0.0,
        volume24hUsd: Double = 0.0,
        usdInr: Double = 86.6,
        pegUsd: Double = 11.55,       // the announced stable target
    ): MarketSnapshot {
        val stable = pegUsd > 0.0 && kotlin.math.abs(priceUsd - pegUsd) <= 0.10
        val ratio = collateralRatio(collateralUsd, totalSupply, priceUsd)
        val mcap = priceUsd * totalSupply
        return MarketSnapshot(
            priceUsd = priceUsd,
            priceInr = usdToInr(priceUsd, usdInr),
            priceStable = stable,
            marketCapUsd = mcap,
            marketCapInr = usdToInr(mcap, usdInr),
            volume24hUsd = volume24hUsd,
            totalSupply = totalSupply,
            holders = holders,
            collateralRatio = ratio,
            collateralRatioPct = (ratio * 100).toInt(),
        )
    }
}
