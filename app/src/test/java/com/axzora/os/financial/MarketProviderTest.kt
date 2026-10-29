package com.axzora.os.financial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Happy Paisa market + collateral provider. */
class MarketProviderTest {

    @Test
    fun collateralRatioIsCollateralOverCirculation() {
        // $100k collateral vs 10,000 HP at $11.55 -> circ $115,500 -> ratio ~0.866
        val r = MarketProvider.collateralRatio(100_000.0, 10_000.0, 11.55)
        assertEquals(0.866, r, 0.001)
    }

    @Test
    fun collateralRatioZeroWhenNoCirculation() {
        assertEquals(0.0, MarketProvider.collateralRatio(100.0, 0.0, 11.55), 0.0001)
    }

    @Test
    fun usdToInrConverts() {
        assertEquals(1155.0, MarketProvider.usdToInr(11.55, 100.0), 0.0001)
    }

    @Test
    fun snapshotComputesMarketCapAndMarksStable() {
        val s = MarketProvider.snapshot(totalSupply = 18_900.0, holders = 515, collateralUsd = 250_000.0, priceUsd = 11.55)
        assertEquals(11.55, s.priceUsd, 0.0001)
        assertEquals(11.55 * 18_900, s.marketCapUsd, 0.0001)
        assertEquals(18_900.0, s.totalSupply, 0.0001)
        assertEquals(515, s.holders)
        assertTrue(s.priceStable)
        assertEquals((250_000.0 / (11.55 * 18_900.0) * 100).toInt(), s.collateralRatioPct)
    }

    @Test
    fun zeroDataIsNotStableAndLoadsZeros() {
        val s = MarketProvider.snapshot()
        assertEquals(0.0, s.priceUsd, 0.0001)
        assertEquals(0, s.holders)
        assertFalse(s.priceStable)
        assertEquals(0, s.collateralRatioPct)
    }
}
