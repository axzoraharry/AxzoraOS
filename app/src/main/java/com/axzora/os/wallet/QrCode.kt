package com.axzora.os.wallet

/**
 * Minimal QR encoder (byte mode, error-correction level L) sufficient for the
 * short JSON vouchers this app produces. Written by hand so the project keeps
 * zero third-party dependencies.
 *
 * Supports versions 1-10 (up to ~170 bytes at level L), which comfortably
 * covers a voucher payload.
 */
object QrCode {

    /** Returns a square matrix; true = dark module. */
    fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = pickVersion(data.size) ?: error("payload too large for QR v1-10")
        val size = 17 + version * 4

        val bits = ArrayList<Boolean>(data.size * 8 + 32)
        // Mode indicator 0100 (byte) + character count (8 bits for v1-9, 16 for v10+)
        appendBits(bits, 0b0100, 4)
        appendBits(bits, data.size, if (version < 10) 8 else 16)
        for (b in data) appendBits(bits, b.toInt() and 0xFF, 8)

        val capacityBits = dataCodewords(version) * 8
        // Terminator
        repeat(minOf(4, capacityBits - bits.size)) { bits.add(false) }
        // Pad to byte boundary
        while (bits.size % 8 != 0) bits.add(false)
        // Pad bytes alternate 0xEC / 0x11
        var pad = 0xEC
        while (bits.size < capacityBits) {
            appendBits(bits, pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }

        val dataBytes = IntArray(bits.size / 8) { i ->
            var v = 0
            for (j in 0 until 8) if (bits[i * 8 + j]) v = v or (1 shl (7 - j))
            v
        }

        val all = addEcc(dataBytes, version)
        val matrix = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }
        placeFunctionPatterns(matrix, reserved, version, size)
        placeData(matrix, reserved, all, size)
        applyMaskAndFormat(matrix, reserved, size)
        return matrix
    }

    // ---- bit helpers ----

    private fun appendBits(out: MutableList<Boolean>, value: Int, count: Int) {
        for (i in count - 1 downTo 0) out.add((value ushr i) and 1 == 1)
    }

    // ---- capacity tables (level L) ----

    /** Total codewords per version 1-10. */
    private val TOTAL = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    /** EC codewords per block, level L, versions 1-10. */
    private val ECC_PER_BLOCK = intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18)
    /** Number of EC blocks, level L, versions 1-10. */
    private val BLOCKS = intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4)

    private fun dataCodewords(v: Int) = TOTAL[v - 1] - ECC_PER_BLOCK[v - 1] * BLOCKS[v - 1]

    private fun pickVersion(byteLen: Int): Int? {
        for (v in 1..10) {
            val headerBits = 4 + if (v < 10) 8 else 16
            if (dataCodewords(v) * 8 >= headerBits + byteLen * 8) return v
        }
        return null
    }

    // ---- Reed-Solomon ----

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun rsGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor mul(poly[j], EXP[i])
                next[j + 1] = next[j + 1] xor poly[j]
            }
            poly = next
        }
        return poly
    }

    private fun rsEncode(data: IntArray, ecLen: Int): IntArray {
        val gen = rsGenerator(ecLen)
        val res = IntArray(ecLen)
        for (b in data) {
            val factor = b xor res[0]
            System.arraycopy(res, 1, res, 0, ecLen - 1)
            res[ecLen - 1] = 0
            for (i in 0 until ecLen) res[i] = res[i] xor mul(gen[i + 1], factor)
        }
        return res
    }

    private fun addEcc(data: IntArray, version: Int): IntArray {
        val numBlocks = BLOCKS[version - 1]
        val ecLen = ECC_PER_BLOCK[version - 1]
        val shortLen = data.size / numBlocks
        val numLong = data.size % numBlocks

        val blocks = ArrayList<IntArray>()
        val eccs = ArrayList<IntArray>()
        var pos = 0
        for (i in 0 until numBlocks) {
            val len = shortLen + if (i >= numBlocks - numLong) 1 else 0
            val blk = data.copyOfRange(pos, pos + len)
            pos += len
            blocks.add(blk)
            eccs.add(rsEncode(blk, ecLen))
        }

        val out = ArrayList<Int>(TOTAL[version - 1])
        val maxLen = blocks.maxOf { it.size }
        for (i in 0 until maxLen) for (b in blocks) if (i < b.size) out.add(b[i])
        for (i in 0 until ecLen) for (e in eccs) out.add(e[i])
        return out.toIntArray()
    }

    // ---- layout ----

    private fun placeFunctionPatterns(
        m: Array<BooleanArray>, res: Array<BooleanArray>, version: Int, size: Int,
    ) {
        fun finder(r: Int, c: Int) {
            for (dr in -1..7) for (dc in -1..7) {
                val rr = r + dr
                val cc = c + dc
                if (rr in 0 until size && cc in 0 until size) {
                    val inRing = dr in 0..6 && dc in 0..6
                    val dark = inRing && (dr == 0 || dr == 6 || dc == 0 || dc == 6 ||
                        (dr in 2..4 && dc in 2..4))
                    m[rr][cc] = dark
                    res[rr][cc] = true
                }
            }
        }
        finder(0, 0); finder(0, size - 7); finder(size - 7, 0)

        // timing
        for (i in 0 until size) {
            if (!res[6][i]) { m[6][i] = i % 2 == 0; res[6][i] = true }
            if (!res[i][6]) { m[i][6] = i % 2 == 0; res[i][6] = true }
        }
        // dark module
        m[size - 8][8] = true; res[size - 8][8] = true

        // alignment pattern (single, versions 2-6 style; adequate for v<=10 here)
        if (version >= 2) {
            val pos = size - 7
            for (dr in -2..2) for (dc in -2..2) {
                val rr = pos + dr
                val cc = pos + dc
                if (rr in 0 until size && cc in 0 until size && !res[rr][cc]) {
                    m[rr][cc] = dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0)
                    res[rr][cc] = true
                }
            }
        }
        // reserve format info areas
        for (i in 0..8) {
            if (i != 6) { res[8][i] = true; res[i][8] = true }
        }
        for (i in 0..7) { res[8][size - 1 - i] = true; res[size - 1 - i][8] = true }
    }

    private fun placeData(m: Array<BooleanArray>, res: Array<BooleanArray>, data: IntArray, size: Int) {
        var bitIdx = 0
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            val rows = if (upward) (size - 1) downTo 0 else 0 until size
            for (row in rows) {
                for (c in intArrayOf(col, col - 1)) {
                    if (res[row][c]) continue
                    val byteI = bitIdx / 8
                    val bit = if (byteI < data.size) (data[byteI] ushr (7 - bitIdx % 8)) and 1 else 0
                    m[row][c] = bit == 1
                    bitIdx++
                }
            }
            col -= 2
            upward = !upward
        }
    }

    private fun applyMaskAndFormat(m: Array<BooleanArray>, res: Array<BooleanArray>, size: Int) {
        // Mask 0: (row + col) % 2 == 0
        for (r in 0 until size) for (c in 0 until size) {
            if (!res[r][c] && (r + c) % 2 == 0) m[r][c] = !m[r][c]
        }
        // Format info for level L (01) + mask 0 -> 0b01000, BCH -> 0x77C4
        val format = 0x77C4
        for (i in 0..5) { m[8][i] = bitOf(format, i); m[size - 1 - i][8] = bitOf(format, i) }
        m[8][7] = bitOf(format, 6); m[8][8] = bitOf(format, 7); m[7][8] = bitOf(format, 8)
        for (i in 9..14) { m[14 - i][8] = bitOf(format, i); m[8][size - 15 + i] = bitOf(format, i) }
    }

    private fun bitOf(v: Int, i: Int) = (v ushr i) and 1 == 1
}
