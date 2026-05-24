package com.example.overlay_mobile

import kotlin.math.sqrt

/**
 * HD Matrix Analyzer — secondary post-process overlay drawn on the full-resolution RGBA frame.
 *
 * Analysis runs on a 320 px-wide luma downscale every [FRAME_SKIP] frames to stay under 3 ms.
 * Results (marker positions, chaos score) are cached and re-drawn every frame so the overlay
 * remains visible between analysis passes without running Sobel at full rate.
 *
 * Coordinate system:
 *   Analysis is done on the [TARGET_W] × downH downscale.
 *   Before drawing, every marker position is scaled back to full-frame pixels using
 *   scaleX = fullW / downW  and  scaleY = fullH / downH.
 *
 * Marker types (detected per [TILE]×[TILE] tile in the downscale):
 *   Angular tile  — high gradient orientation variance → red crosshair.
 *   Smooth tile   — low mean gradient magnitude        → cyan ring.
 *
 * Symmetry lines:
 *   Dashed magenta Bresenham lines connect each angular marker to its nearest smooth
 *   neighbour (and vice-versa), simulating SUSY "vector" annotations.
 *
 * Instability border:
 *   Global gradient variance (Long accumulator, single sqrt) is compared to [CHAOS_THRESHOLD].
 *   When exceeded, a 2-pixel-thick red border is drawn around the full frame;
 *   [flashToggle] alternates the border between bright and dim red each draw pass.
 */
class MatrixAnalyzerTransform : PixelTransform() {

    // ── Tuning constants ───────────────────────────────────────────────────────

    private companion object {
        const val TARGET_W        = 320      // downscale width (pixels)
        const val FRAME_SKIP      = 6        // analyse every Nth frame
        const val TILE            = 16       // tile size for histogram (pixels in downscale)
        const val MAX_MARKERS     = 100      // cap per marker type
        const val ANGULAR_DIRS    = 5        // min unique direction bins → angular
        const val SMOOTH_MAG      = 12       // max mean magnitude → smooth
        const val CHAOS_THRESHOLD = 18_000.0 // global gradient stddev threshold
    }

    // ── Per-downscale scratch buffers (allocated once) ─────────────────────────

    // Sized for worst-case: TARGET_W × (TARGET_W * 16/9) ≈ 320 × 569 ≈ 182 080 px
    private val MAX_DOWN_PX = TARGET_W * 600

    private val downLuma = ByteArray(MAX_DOWN_PX)      // 8-bit luma of downscale
    private val gxBuf    = ShortArray(MAX_DOWN_PX)     // Sobel Gx (fits in Short: max ±1020)
    private val gyBuf    = ShortArray(MAX_DOWN_PX)     // Sobel Gy

    // Marker positions in *downscale* pixel space
    private val angX = IntArray(MAX_MARKERS)
    private val angY = IntArray(MAX_MARKERS)
    private val smoX = IntArray(MAX_MARKERS)
    private val smoY = IntArray(MAX_MARKERS)

    // ── Volatile cached results (written by analysis, read by draw) ────────────

    @Volatile private var angCount    = 0
    @Volatile private var smoCount    = 0
    @Volatile private var downW       = 0
    @Volatile private var downH       = 0
    @Volatile private var chaosActive = false
    @Volatile private var flashToggle = false

    // ── Frame counter ──────────────────────────────────────────────────────────

    private var frameN = 0

    // ── Pre-allocated ring offsets (8-point Midpoint circle, r=6 in downscale) ─

    private val RING_DX = intArrayOf( 6,  4,  0, -4, -6, -4,  0,  4)
    private val RING_DY = intArrayOf( 0,  4,  6,  4,  0, -4, -6, -4)

    // ── PixelTransform override ────────────────────────────────────────────────

    override fun transformInPlace(rgba: ByteArray, width: Int, height: Int) {
        frameN++
        if (frameN % FRAME_SKIP == 0) analyse(rgba, width, height)
        drawOverlay(rgba, width, height)
    }

    // ── Analysis pipeline ──────────────────────────────────────────────────────

    private fun analyse(rgba: ByteArray, fullW: Int, fullH: Int) {
        // Compute downscale dimensions
        val dw = TARGET_W.coerceAtMost(fullW)
        val dh = (fullH * dw.toFloat() / fullW + 0.5f).toInt().coerceAtLeast(1)
        if (dw * dh > MAX_DOWN_PX) return      // safety guard
        downW = dw
        downH = dh

        downscaleLuma(rgba, fullW, fullH, dw, dh)
        sobelLuma(dw, dh)

        var aC = 0
        var sC = 0
        var magSum  = 0L
        var magSum2 = 0L

        val tilesCols = (dw + TILE - 1) / TILE
        val tilesRows = (dh + TILE - 1) / TILE

        for (ty in 0 until tilesRows) {
            for (tx in 0 until tilesCols) {
                val x0 = tx * TILE
                val y0 = ty * TILE
                val x1 = (x0 + TILE).coerceAtMost(dw)
                val y1 = (y0 + TILE).coerceAtMost(dh)

                var tileMagSum = 0L
                var tileCount  = 0
                val dirBins    = IntArray(8)  // local, tiny — stack-allocated by JIT

                for (y in y0 until y1) {
                    val rowOff = y * dw
                    for (x in x0 until x1) {
                        val i   = rowOff + x
                        val gx  = gxBuf[i].toInt()
                        val gy  = gyBuf[i].toInt()
                        val mag = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)  // L1
                        tileMagSum += mag
                        tileCount++
                        // accumulate global stats
                        magSum  += mag
                        magSum2 += mag.toLong() * mag
                        // 8-bin direction (no trig, integer quadrant + octant split)
                        dirBins[dirBin8(gx, gy)]++
                    }
                }

                val meanMag = if (tileCount > 0) (tileMagSum / tileCount).toInt() else 0
                val cx = (x0 + x1) / 2
                val cy = (y0 + y1) / 2

                if (meanMag < SMOOTH_MAG) {
                    if (sC < MAX_MARKERS) { smoX[sC] = cx; smoY[sC] = cy; sC++ }
                } else {
                    val uniqueDirs = dirBins.count { it > 0 }
                    if (uniqueDirs >= ANGULAR_DIRS && aC < MAX_MARKERS) {
                        angX[aC] = cx; angY[aC] = cy; aC++
                    }
                }
            }
        }

        angCount = aC
        smoCount = sC

        // Global chaos: variance approximation via E[x²] − E[x]²
        val totalPx = dw * dh
        if (totalPx > 0) {
            val mean     = magSum.toDouble() / totalPx
            val mean2    = magSum2.toDouble() / totalPx
            val variance = mean2 - mean * mean
            val stddev   = sqrt(variance.coerceAtLeast(0.0))
            chaosActive  = stddev > CHAOS_THRESHOLD
            flashToggle  = !flashToggle
        }
    }

    // ── Draw cached results onto full-res frame ────────────────────────────────

    private fun drawOverlay(rgba: ByteArray, fullW: Int, fullH: Int) {
        val dw = downW
        val dh = downH
        if (dw == 0 || dh == 0) return

        val scaleX = fullW.toFloat() / dw
        val scaleY = fullH.toFloat() / dh

        val aC = angCount
        val sC = smoCount

        // Angular markers → red crosshairs
        for (i in 0 until aC) {
            val px = (angX[i] * scaleX).toInt()
            val py = (angY[i] * scaleY).toInt()
            drawCrosshair(rgba, fullW, fullH, px, py)
        }

        // Smooth markers → cyan rings
        for (i in 0 until sC) {
            val px = (smoX[i] * scaleX).toInt()
            val py = (smoY[i] * scaleY).toInt()
            drawRing(rgba, fullW, fullH, px, py, scaleX)
        }

        // Vector lines: each angular marker → nearest smooth marker
        for (a in 0 until aC) {
            val ax = (angX[a] * scaleX).toInt()
            val ay = (angY[a] * scaleY).toInt()
            var bestDist = Int.MAX_VALUE
            var bestS    = -1
            for (s in 0 until sC) {
                val dx   = angX[a] - smoX[s]
                val dy   = angY[a] - smoY[s]
                val dist = dx * dx + dy * dy
                if (dist < bestDist) { bestDist = dist; bestS = s }
            }
            if (bestS >= 0) {
                val bx = (smoX[bestS] * scaleX).toInt()
                val by = (smoY[bestS] * scaleY).toInt()
                drawDashedLine(rgba, fullW, fullH, ax, ay, bx, by)
            }
        }

        // Instability border
        if (chaosActive) drawBorder(rgba, fullW, fullH)
    }

    // ── Downscale: nearest-neighbour luma extraction ───────────────────────────

    private fun downscaleLuma(rgba: ByteArray, fw: Int, fh: Int, dw: Int, dh: Int) {
        for (dy in 0 until dh) {
            val sy      = (dy * fh.toLong() / dh).toInt()
            val srcRowOff = sy * fw
            val dstRowOff = dy * dw
            for (dx in 0 until dw) {
                val sx   = (dx * fw.toLong() / dw).toInt()
                val si   = (srcRowOff + sx) * 4
                val r    = rgba[si    ].toInt() and 0xFF
                val g    = rgba[si + 1].toInt() and 0xFF
                val b    = rgba[si + 2].toInt() and 0xFF
                // BT.601 integer luma: (77R + 150G + 29B) >> 8
                downLuma[dstRowOff + dx] = ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
            }
        }
    }

    // ── 3×3 Sobel on downLuma → gxBuf / gyBuf (border pixels = 0) ─────────────

    private fun sobelLuma(dw: Int, dh: Int) {
        // Zero borders
        for (x in 0 until dw) { gxBuf[x] = 0; gyBuf[x] = 0 }
        val lastRow = (dh - 1) * dw
        for (x in 0 until dw) { gxBuf[lastRow + x] = 0; gyBuf[lastRow + x] = 0 }
        for (y in 0 until dh) { gxBuf[y * dw] = 0; gyBuf[y * dw] = 0 }
        for (y in 0 until dh) { gxBuf[y * dw + dw - 1] = 0; gyBuf[y * dw + dw - 1] = 0 }

        for (y in 1 until dh - 1) {
            val row0 = (y - 1) * dw
            val row1 = y * dw
            val row2 = (y + 1) * dw
            for (x in 1 until dw - 1) {
                val p00 = downLuma[row0 + x - 1].toInt() and 0xFF
                val p01 = downLuma[row0 + x    ].toInt() and 0xFF
                val p02 = downLuma[row0 + x + 1].toInt() and 0xFF
                val p10 = downLuma[row1 + x - 1].toInt() and 0xFF
                val p12 = downLuma[row1 + x + 1].toInt() and 0xFF
                val p20 = downLuma[row2 + x - 1].toInt() and 0xFF
                val p21 = downLuma[row2 + x    ].toInt() and 0xFF
                val p22 = downLuma[row2 + x + 1].toInt() and 0xFF
                // Gx = (p02 - p00) + 2*(p12 - p10) + (p22 - p20)   range ±1020, fits Short
                // Gy = (p20 - p00) + 2*(p21 - p01) + (p22 - p02)
                gxBuf[row1 + x] = ((p02 - p00) + 2 * (p12 - p10) + (p22 - p20)).toShort()
                gyBuf[row1 + x] = ((p20 - p00) + 2 * (p21 - p01) + (p22 - p02)).toShort()
            }
        }
    }

    // ── 8-bin gradient direction (integer, no trig) ────────────────────────────

    // Divides the plane into 8 octants using absolute values and sign comparison.
    //   Bin 0 = E, 1 = NE, 2 = N, 3 = NW, 4 = W, 5 = SW, 6 = S, 7 = SE
    private fun dirBin8(gx: Int, gy: Int): Int {
        if (gx == 0 && gy == 0) return 0
        val ax = if (gx < 0) -gx else gx
        val ay = if (gy < 0) -gy else gy
        // Diagonal split: if |gx| > |gy|, we're in E or W half; else N or S half
        return if (ax > ay) {
            if (gx >= 0) (if (gy >= 0) 0 else 7) else (if (gy >= 0) 3 else 4)
        } else {
            if (gy >= 0) (if (gx >= 0) 1 else 2) else (if (gx >= 0) 6 else 5)
        }
    }

    // ── Primitive draw calls (write directly into full-res RGBA buffer) ─────────

    private fun writePx(rgba: ByteArray, w: Int, h: Int, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        val i = (y * w + x) * 4
        rgba[i    ] = r.toByte()
        rgba[i + 1] = g.toByte()
        rgba[i + 2] = b.toByte()
        // leave alpha (rgba[i+3]) unchanged
    }

    private fun drawCrosshair(rgba: ByteArray, w: Int, h: Int, cx: Int, cy: Int) {
        for (d in -4..4) {
            writePx(rgba, w, h, cx + d, cy, 255, 0, 0)
            writePx(rgba, w, h, cx, cy + d, 255, 0, 0)
        }
    }

    private fun drawRing(rgba: ByteArray, w: Int, h: Int, cx: Int, cy: Int, scaleX: Float) {
        // Scale ring radius roughly with the downscale→fullscale factor
        val r = (6 * scaleX + 0.5f).toInt().coerceAtLeast(4)
        // Midpoint circle algorithm — no trig
        var rx = r; var ry = 0; var err = 0
        while (rx >= ry) {
            writePx(rgba, w, h, cx + rx, cy + ry, 0, 230, 220)
            writePx(rgba, w, h, cx + ry, cy + rx, 0, 230, 220)
            writePx(rgba, w, h, cx - ry, cy + rx, 0, 230, 220)
            writePx(rgba, w, h, cx - rx, cy + ry, 0, 230, 220)
            writePx(rgba, w, h, cx - rx, cy - ry, 0, 230, 220)
            writePx(rgba, w, h, cx - ry, cy - rx, 0, 230, 220)
            writePx(rgba, w, h, cx + ry, cy - rx, 0, 230, 220)
            writePx(rgba, w, h, cx + rx, cy - ry, 0, 230, 220)
            ry++
            err += 2 * ry + 1
            if (2 * (err - rx) + 1 > 0) { rx--; err += 1 - 2 * rx }
        }
    }

    // Bresenham line with step%6<3 dash pattern, magenta colour
    private fun drawDashedLine(rgba: ByteArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0; var y = y0
        val dx =  if (x1 > x0) x1 - x0 else x0 - x1
        val dy = -(if (y1 > y0) y1 - y0 else y0 - y1)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        var step = 0
        while (true) {
            if (step % 6 < 3) writePx(rgba, w, h, x, y, 200, 0, 200)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { if (x == x1) break; err += dy; x += sx }
            if (e2 <= dx) { if (y == y1) break; err += dx; y += sy }
            step++
        }
    }

    // 2-pixel thick border; alternates bright/dim red with flashToggle
    private fun drawBorder(rgba: ByteArray, w: Int, h: Int) {
        val r = if (flashToggle) 255 else 160
        for (row in 0..1) {
            for (x in 0 until w) {
                writePx(rgba, w, h, x, row,         r, 0, 0)
                writePx(rgba, w, h, x, h - 1 - row, r, 0, 0)
            }
        }
        for (col in 0..1) {
            for (y in 0 until h) {
                writePx(rgba, w, h, col,         y, r, 0, 0)
                writePx(rgba, w, h, w - 1 - col, y, r, 0, 0)
            }
        }
    }
}
