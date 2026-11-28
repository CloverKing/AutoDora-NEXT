package com.autodora.next.vision

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.Element
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shift-tolerant 24x24 canonical monochrome silhouette & core-brightness matcher calibrated from
 * official ToS Encyclopedia Skill #048 (`幽閉空間`).
 *
 * Operates completely offline in ~1.2ms per monochrome board:
 *  - Excludes outer golden Puzzle Shield frames (`r <= 8.5` inside 24x24 cell grid).
 *  - Combines 2-pass 5x5 local high-pass emblem structure (`weight = 0.55`) with cell-p95-normalized
 *    inner core luminance (`r <= 6.0`, `weight = 1.45`) across 3x3 micro-shifts (`±2.2%` cell width).
 *  - Works standalone in Pure-CV mode AND fuses 50/50 with `BoardMasterGridNetV2` probabilities in Hybrid mode.
 */
object MonochromeOrbMatcher {

    private const val G = 24
    private const val HP_QUANT_SCALE = 48f
    private const val RAW_QUANT_SCALE = 100f

    private val CIRC_INDICES = intArrayOf(104, 105, 106, 107, 108, 109, 110, 111, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 258, 259, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 316, 317, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 331, 340, 341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 377, 378, 379, 389, 390, 391, 392, 393, 394, 395, 396, 397, 398, 399, 400, 401, 402, 414, 415, 416, 417, 418, 419, 420, 421, 422, 423, 424, 425, 439, 440, 441, 442, 443, 444, 445, 446, 447, 448, 464, 465, 466, 467, 468, 469, 470, 471)
    private val CORE_INDICES = intArrayOf(154, 155, 156, 157, 176, 177, 178, 179, 180, 181, 182, 183, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 392, 393, 394, 395, 396, 397, 398, 399, 418, 419, 420, 421)

    private val TPL_HP_Q = arrayOf(
        byteArrayOf(19, -25, -38, -29, -32, -33, -32, -29, 12, -36, -40, -34, -10, -5, -34, -23, -36, -22, 29, -35, -34, -37, -24, -9, 16, -41, -32, -38, -37, -19, 83, -27, -31, -15, 32, 47, 40, 51, -5, -34, -45, -42, -42, -10, 44, 25, -21, -33, -17, 65, 69, 81, 82, 49, 24, -19, -47, -41, -45, 14, 69, -16, -17, -35, -34, -6, -14, 3, 61, 58, 62, 42, -25, -43, -27, -13, 26, -12, -22, -35, -38, -47, -56, -59, 7, 55, 45, 70, 24, -47, -11, 14, -11, -8, -26, -37, -41, -27, -29, -61, -52, 54, 55, 69, 30, -46, -8, 15, -14, -11, -31, -41, -2, 54, 46, 0, -65, -6, 32, 32, -17, -43, -16, 6, -17, -13, -35, -43, 64, 89, 65, 52, -28, -61, -31, -29, -42, -39, -35, -3, -16, -9, -29, -46, 54, 68, 40, 43, 27, -62, -55, -47, -40, -38, -40, -9, -11, 0, -13, -50, -17, 52, 46, 38, 51, -2, -3, 6, -12, -40, -35, 3, -1, 2, -41, -53, -6, 32, 49, 61, 63, 67, 67, 5, -46, -34, 8, -4, -55, -54, -22, 7, 18, 17, 21, 13, -23, -42, 26, -53, -58, -53, -50, -40, -49, -56, -57, -24, -12, -31, -43, -63, -53, -55, -54, -11),
        byteArrayOf(-16, 45, -2, -37, -11, -8, 3, 13, 6, 6, 16, 13, 44, 37, 11, 13, 20, 83, 72, 38, -2, -6, 1, 54, 86, 39, -5, -3, 25, -19, 29, 40, -1, -10, -14, -19, -6, 64, 78, 18, -13, -19, -30, -27, 9, -7, -13, -29, -23, -25, -35, -43, -5, 67, 53, -16, -36, -34, -41, -27, 23, -11, -39, -30, -31, -35, -42, -53, 7, 49, 54, -21, -45, -40, -45, -40, 33, 8, -35, -32, 3, -9, -45, -42, 58, 36, 43, -27, 16, -39, -50, -47, 32, 20, -44, -40, 3, 58, -31, 23, 53, 23, 17, -54, 80, 5, -55, -53, 36, 30, -58, -47, -10, 83, -8, 61, 19, 17, -29, -54, 84, 53, -58, -56, 39, 25, -65, -55, 43, 67, -4, 41, 7, 18, -61, 27, 76, 41, -61, -59, 46, 7, -61, -60, 60, 55, -24, 33, 9, 21, -44, 82, 51, -19, -66, -57, 63, 11, -47, -53, -7, 51, 0, 12, 25, 32, -3, 60, 15, -64, -76, -35, 54, 2, -45, -64, -4, 25, -31, 5, 29, 30, 6, -26, -67, -68, 50, -42, -71, -60, -16, -66, -51, -25, -7, -48, -53, -42, -6, -62, -16, -61, -75, -70, -60, -46, -54, -43, -10, 45, -19, -16, -8, -2, 14, -7),
        byteArrayOf(19, 4, 28, 10, 29, 36, 46, 88, 16, 42, 33, 20, 3, -9, 0, 31, 120, 40, -4, 1, 71, 91, 58, 25, -19, -6, 7, 72, -26, -37, 60, -25, 0, 9, 90, 62, 48, -3, -21, -7, 17, -27, -54, -34, 2, -7, -34, -17, -40, 29, 47, 42, 37, -29, 6, -20, -21, -54, -78, 75, -34, -21, -26, -37, -33, -23, 16, 34, 65, -18, -2, -29, -13, -40, -86, -14, -7, -11, -20, -21, -21, -13, -19, -8, 17, -15, -23, 26, 73, -26, -76, -57, 6, 0, -27, -15, 42, 67, 7, -44, -38, -28, 28, 99, 102, -27, -72, -42, 6, -6, -24, 10, 114, 91, 2, -31, -41, -40, 22, 55, 16, -34, -63, 0, -2, -18, -12, 30, 62, 15, -32, 7, 17, -10, -21, -16, -29, -31, -47, 21, -7, -16, -12, -9, -10, -24, -32, 10, 68, 28, 21, -23, -37, -38, -50, 26, 13, -4, -17, -39, -25, -25, -25, -11, 69, 33, 43, -2, -51, -56, -64, 27, 30, -17, -54, -40, -24, -28, -18, 53, 37, 31, 35, -46, -69, -52, -14, -55, -54, -20, -44, -43, -2, 7, 2, 27, -2, -53, -42, -12, -34, -56, -53, -31, -30, -28, -17, 2, 54, -28, -28, -16, -9, 7, -25, -27),
        byteArrayOf(-59, -4, -2, -20, -41, -35, -34, -40, -40, -44, -10, 6, 72, -23, -22, -21, -25, -33, 42, -13, -32, -29, 11, 122, -6, -20, -16, -14, -9, -19, 48, 38, -30, -37, -41, 27, 95, 6, -29, -22, -19, -6, -3, -29, 42, 29, -25, -49, -49, -51, 53, 71, 16, -42, -33, -24, -12, 27, -33, -20, 26, -23, -62, -53, -50, -50, 39, 59, 11, -47, -39, -27, -5, 13, -30, -63, -4, -24, -8, 4, 10, 1, 8, 53, 14, 5, 14, 20, 29, -13, -27, -58, 0, 11, 50, 54, 54, 48, 16, 51, 29, 55, 65, 69, 69, 39, -18, -12, 8, -32, -3, 4, 4, 6, 0, 60, -7, 15, 30, 28, 29, 16, -8, 32, -14, -65, -51, -45, -49, -50, 23, 68, -31, -40, -26, -29, -34, -40, 14, 40, -7, -28, -51, -60, -63, -60, 72, 68, -10, -54, -48, -45, -42, -30, 10, 38, 27, 14, -40, -71, -67, -61, 48, 66, -12, -53, -50, -48, -17, -16, -16, 40, 45, 23, -34, -73, -69, 13, 66, -23, -55, -53, -19, 31, -3, -20, 78, 39, -34, -72, -37, 63, -41, -55, -48, 35, 50, 21, 72, 34, -13, -51, 51, -21, -12, 4, 54, 57, 60, 48, 5, 39, 13, 24, 47, 68),
        byteArrayOf(-21, -25, -21, -20, -13, -20, -14, 7, -25, -20, -28, -27, -28, -24, -24, -10, 34, -37, 34, -34, -34, -35, -29, -19, -17, -24, -18, -9, -41, 6, 34, -1, -37, -40, -10, 24, 33, 38, 33, 21, -38, -44, -34, 11, -30, -14, -40, -43, -13, 46, 59, 53, 65, 80, 71, 5, -42, -50, -1, 10, -15, -52, -50, -48, 49, 55, 26, 28, 39, 15, 24, 11, -39, -50, -29, 63, 6, -48, -53, -36, 75, 28, 12, 32, -11, -47, -41, -31, -37, -49, -41, 64, 23, -54, -52, -22, 64, 20, 9, 38, -55, -54, -41, -35, -36, -50, -28, 51, 38, -64, -52, -32, 67, 23, 9, 35, -31, -54, -42, -36, -38, -52, -21, 52, 38, -65, -53, -52, 61, 36, 14, 27, 24, -11, -6, 17, -41, -56, -27, 54, 21, -56, -52, -61, -1, 49, 35, 29, 49, 57, 66, 41, -49, -65, -28, 44, 13, -39, -43, -66, -61, 0, 27, 41, 48, 55, 38, -29, -62, -70, -7, 34, 14, -19, -61, -75, -52, -34, -9, -5, -17, -48, -65, -64, -51, 27, 26, -10, -62, -58, -79, -71, -67, -68, -63, -61, -18, 5, 32, 3, -28, -57, -57, -45, -47, -28, -19, 31, 56, 37, 17, 12, 23, 21, 36, 32),
        byteArrayOf(-31, -31, -31, -31, -32, -31, -29, -30, -36, -38, -38, -37, -38, -38, -36, -31, -31, -31, 18, 25, 15, -19, -51, -22, 8, 24, 15, -19, -35, -34, 51, 82, 74, 66, 38, -23, 41, 60, 73, 69, 47, -31, -38, -37, -34, 114, 59, 27, 28, 44, 18, 53, 33, 29, 44, 83, 11, -40, -39, -42, -13, 90, 19, 0, 0, 3, 11, 6, 2, 2, 12, 52, 56, -44, -42, -43, -15, 77, 9, -1, -2, -7, -6, -6, -7, -3, 7, 46, 63, -48, -44, -45, -37, 78, 20, 7, 2, -9, -8, -7, -6, 0, 15, 64, 23, -47, -45, -47, -44, 33, 53, 25, 5, -6, -6, -5, -2, 11, 42, 51, -29, -43, -45, -49, -40, -29, 40, 51, 15, 4, 1, 1, 9, 38, 59, -13, -46, -37, -46, -53, -39, -41, -22, 24, 42, 26, 12, 16, 39, 51, 5, -46, -44, -37, -49, -57, -42, -42, -47, -38, 10, 43, 42, 51, 41, -4, -53, -47, -36, -40, -55, -59, -45, -52, -59, -56, -9, 58, 20, -23, -65, -60, -51, -12, -15, -56, -47, -66, -74, -75, -12, -56, -80, -74, -66, -33, 7, 8, -25, -43, -49, -48, -48, -45, -34, -19, -1, 4, 25, 18, 12, 13, 15, 26, 37, 18),
    )

    private val TPL_RAW_Q = arrayOf(
        byteArrayOf(13, 24, 41, 2, 18, 52, 64, 62, 72, 32, 10, 1, 3, 16, 76, 84, 97, 101, 77, 56, 23, 1, 0, 3, 28, 28, 47, 90, 88, 88, 69, 18, 10, 1, 0, 0, 0, 5, 54, 87, 77, 89, 52, 1, 8, 1, 1, 16, 21, 4, 14, 86, 82, 87, 56, 1, 6, 1, 31, 75, 76, 48, 5, 44, 64, 60, 21, 1, 4, 2, 80, 105, 93, 86, 30, 4, 19, 15, 1, 0, 2, 76, 91, 77, 81, 68, 2, 0, 0, 0, 1, 28, 80, 80, 76, 83, 42, 36, 37, 21, 2, 38, 67, 80, 89, 86, 85, 82, 27, 48, 56, 55),
        byteArrayOf(56, 75, 87, 72, 56, 54, 54, 60, 87, 93, 71, 59, 53, 54, 54, 52, 52, 68, 94, 87, 61, 53, 54, 53, 53, 53, 52, 75, 91, 91, 62, 52, 57, 55, 67, 65, 55, 58, 95, 89, 89, 62, 74, 53, 55, 54, 70, 91, 63, 84, 97, 86, 83, 55, 98, 70, 53, 55, 69, 103, 74, 100, 87, 86, 68, 56, 101, 88, 53, 54, 89, 99, 76, 94, 83, 86, 57, 84, 99, 85, 55, 96, 95, 69, 90, 83, 86, 61, 103, 91, 59, 74, 94, 76, 81, 86, 88, 74, 95, 78, 55, 75, 84, 65, 78, 86, 85, 76, 70, 53, 59, 68),
        byteArrayOf(84, 75, 63, 65, 74, 98, 91, 86, 72, 65, 67, 73, 66, 62, 84, 89, 88, 85, 65, 73, 64, 63, 61, 65, 70, 82, 86, 94, 69, 72, 63, 66, 65, 67, 69, 73, 72, 76, 82, 71, 67, 78, 90, 63, 66, 70, 87, 95, 79, 66, 67, 69, 82, 100, 100, 63, 69, 78, 108, 103, 79, 70, 67, 66, 82, 89, 77, 62, 74, 85, 95, 82, 69, 80, 83, 75, 71, 71, 66, 64, 75, 74, 71, 69, 82, 99, 87, 84, 70, 66, 68, 70, 70, 70, 76, 99, 90, 92, 78, 64, 67, 71, 70, 75, 96, 92, 91, 91, 68, 70, 84, 87),
        byteArrayOf(44, 102, 31, 20, 26, 23, 59, 95, 44, 22, 21, 19, 28, 25, 24, 80, 89, 57, 22, 22, 23, 26, 29, 28, 29, 77, 88, 61, 26, 26, 27, 35, 58, 61, 63, 59, 65, 89, 66, 58, 58, 57, 57, 33, 89, 89, 88, 85, 71, 90, 76, 87, 88, 86, 82, 64, 61, 61, 61, 63, 62, 94, 57, 65, 69, 64, 62, 54, 35, 35, 31, 31, 71, 96, 41, 33, 37, 32, 28, 25, 29, 24, 25, 96, 94, 50, 23, 23, 23, 24, 27, 25, 26, 84, 92, 48, 23, 23, 23, 39, 27, 26, 67, 94, 43, 24, 23, 41, 46, 96, 38, 28),
        byteArrayOf(5, 12, 14, 8, 1, 22, 45, 53, 55, 49, 39, 3, 2, 23, 65, 79, 76, 81, 86, 75, 28, 1, 2, 68, 79, 63, 64, 67, 46, 45, 33, 0, 1, 13, 91, 65, 58, 69, 38, 4, 1, 3, 0, 0, 4, 25, 86, 62, 57, 75, 9, 0, 0, 1, 1, 0, 6, 20, 88, 64, 57, 73, 23, 1, 1, 2, 1, 0, 8, 8, 84, 69, 58, 66, 59, 28, 25, 35, 2, 1, 3, 44, 76, 69, 66, 76, 74, 75, 59, 2, 4, 6, 47, 64, 73, 76, 78, 66, 21, 1, 1, 14, 27, 44, 46, 36, 14, 4, 2, 7, 9, 9),
        byteArrayOf(1, 18, 36, 44, 85, 67, 28, 68, 81, 88, 81, 60, 68, 71, 81, 63, 86, 74, 69, 74, 91, 38, 54, 57, 60, 64, 61, 59, 57, 58, 76, 70, 55, 55, 57, 56, 56, 56, 55, 55, 57, 73, 75, 0, 59, 58, 59, 54, 54, 55, 55, 56, 60, 82, 50, 1, 74, 64, 58, 53, 54, 55, 55, 59, 72, 70, 15, 2, 61, 74, 58, 56, 56, 55, 58, 71, 78, 26, 1, 5, 54, 70, 65, 59, 61, 72, 75, 42, 1, 0, 12, 49, 73, 75, 79, 72, 40, 2, 0, 7, 7, 42, 85, 62, 33, 2, 0, 2, 47, 18, 1, 2),
    )

    /**
     * Checks whether the battle board is currently under a board-wide Monochrome (`幽閉空間` / `黑白符石`) effect.
     * Requires >= 55% of valid non-dark-hole cells to exhibit near-zero saturation (`S <= 0.14`) with visible
     * greyscale rune rims/bodies (`V >= 0.24`).
     */
    fun isBoardMonochrome(frame: VisionFrame, geometry: BoardGeometry): Boolean {
        val cols = geometry.cols
        val rows = geometry.rows
        val total = cols * rows
        if (total < 12) return false
        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        var monoCells = 0
        var activeCells = 0

        for (r in 0 until rows) {
            val cy = geometry.cellCenterY(r)
            if (cy - ch * 0.4f < frame.minAccessibleY || cy + ch * 0.4f >= frame.height) continue
            for (c in 0 until cols) {
                val cx = geometry.cellCenterX(c)
                var satSum = 0f
                var maxV = 0f
                var vSum = 0f
                var cnt = 0
                for (iy in -2..2) {
                    val py = (cy + iy * 0.16f * ch).roundToInt().coerceIn(0, frame.height - 1)
                    for (ix in -2..2) {
                        // Exclude top-right B&W race medallion quadrant so race badges never fake monochrome!
                        if (ix > 0 && iy < 0) continue
                        val px = (cx + ix * 0.16f * cw).roundToInt().coerceIn(0, frame.width - 1)
                        val argb = frame.getArgb(px, py)
                        val rr = ((argb ushr 16) and 0xFF) / 255f
                        val gg = ((argb ushr 8) and 0xFF) / 255f
                        val bb = (argb and 0xFF) / 255f
                        val mx = max(rr, max(gg, bb))
                        val mn = min(rr, min(gg, bb))
                        val s = if (mx > 1e-4f) (mx - mn) / mx else 0f
                        satSum += s
                        vSum += mx
                        if (mx > maxV) maxV = mx
                        cnt++
                    }
                }
                if (cnt == 0) continue
                val meanV = vSum / cnt
                val meanS = satSum / cnt
                if (maxV < 0.22f && meanV < 0.14f) continue // Empty/shattered/modal dark cell
                activeCells++
                if (meanS <= 0.12f && maxV >= 0.26f) {
                    monoCells++
                }
            }
        }
        return activeCells >= 12 && monoCells >= (activeCells * 0.58f).toInt()
    }

    /**
     * Returns 6-element probability distribution `[Water, Fire, Wood, Light, Dark, Heart]` for cell `(row, col)`.
     */
    fun classifyMonochromeCellProbs(
        frame: VisionFrame,
        geometry: BoardGeometry,
        row: Int,
        col: Int
    ): FloatArray {
        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        val baseX0 = geometry.leftPx + col * cw
        val baseY0 = geometry.topPx + row * ch
        val shiftX = max(1f, cw * 0.022f)
        val shiftY = max(1f, ch * 0.022f)

        val bestDists = FloatArray(6) { 1e9f }
        val sm = FloatArray(G * G)
        val tmpA = FloatArray(G * G)
        val tmpB = FloatArray(G * G)
        val hpVec = FloatArray(CIRC_INDICES.size)
        val circSort = FloatArray(CIRC_INDICES.size)
        val rawVec = FloatArray(CORE_INDICES.size)

        val shifts = floatArrayOf(-1f, 0f, 1f)
        for (syShift in shifts) {
            val y0 = baseY0 + syShift * shiftY
            for (sxShift in shifts) {
                val x0 = baseX0 + sxShift * shiftX
                sampleCellFeatures(frame, x0, y0, cw, ch, sm, tmpA, tmpB, hpVec, circSort, rawVec)
                for (e in 0 until 6) {
                    val tplHp = TPL_HP_Q[e]
                    val tplRaw = TPL_RAW_Q[e]
                    var dHp = 0f
                    for (i in hpVec.indices) {
                        dHp += abs(hpVec[i] - tplHp[i] / HP_QUANT_SCALE)
                    }
                    dHp /= hpVec.size.toFloat()

                    var dRaw = 0f
                    for (i in rawVec.indices) {
                        dRaw += abs(rawVec[i] - tplRaw[i] / RAW_QUANT_SCALE)
                    }
                    dRaw /= rawVec.size.toFloat()

                    val dist = dHp * 0.55f + dRaw * 1.45f
                    if (dist < bestDists[e]) {
                        bestDists[e] = dist
                    }
                }
            }
        }

        var minD = bestDists[0]
        for (e in 1 until 6) if (bestDists[e] < minD) minD = bestDists[e]
        val probs = FloatArray(6)
        var sum = 0f
        for (e in 0 until 6) {
            val p = exp(-((bestDists[e] - minD) / 0.08f).toDouble()).toFloat()
            probs[e] = p
            sum += p
        }
        if (sum > 0f) {
            for (e in 0 until 6) probs[e] /= sum
        }
        return probs
    }

    fun bestElementFromProbs(probs: FloatArray): Pair<Element, Float> {
        var bestIdx = 0
        var bestP = probs[0]
        for (i in 1 until min(6, probs.size)) {
            if (probs[i] > bestP) {
                bestP = probs[i]
                bestIdx = i
            }
        }
        return Element.fromCode(bestIdx) to bestP.coerceIn(0.58f, 0.98f)
    }

    private fun sampleCellFeatures(
        frame: VisionFrame,
        x0: Float,
        y0: Float,
        cw: Float,
        ch: Float,
        sm: FloatArray,
        bufA: FloatArray,
        bufB: FloatArray,
        hpOut: FloatArray,
        circSortBuf: FloatArray,
        rawOut: FloatArray
    ) {
        val maxW = frame.width - 1
        val maxH = frame.height - 1
        val binW = cw / G
        val binH = ch / G
        val scale = 1f / (255f * 4f)

        for (iy in 0 until G) {
            val py0 = (y0 + (iy + 0.25f) * binH).roundToInt().coerceIn(0, maxH)
            val py1 = (y0 + (iy + 0.75f) * binH).roundToInt().coerceIn(0, maxH)
            val rowOff = iy * G
            for (ix in 0 until G) {
                val px0 = (x0 + (ix + 0.25f) * binW).roundToInt().coerceIn(0, maxW)
                val px1 = (x0 + (ix + 0.75f) * binW).roundToInt().coerceIn(0, maxW)
                val c00 = frame.getArgb(px0, py0)
                val c10 = frame.getArgb(px1, py0)
                val c01 = frame.getArgb(px0, py1)
                val c11 = frame.getArgb(px1, py1)
                val lumaSum =
                    (0.299f * ((c00 ushr 16) and 0xFF) + 0.587f * ((c00 ushr 8) and 0xFF) + 0.114f * (c00 and 0xFF)) +
                    (0.299f * ((c10 ushr 16) and 0xFF) + 0.587f * ((c10 ushr 8) and 0xFF) + 0.114f * (c10 and 0xFF)) +
                    (0.299f * ((c01 ushr 16) and 0xFF) + 0.587f * ((c01 ushr 8) and 0xFF) + 0.114f * (c01 and 0xFF)) +
                    (0.299f * ((c11 ushr 16) and 0xFF) + 0.587f * ((c11 ushr 8) and 0xFF) + 0.114f * (c11 and 0xFF))
                sm[rowOff + ix] = lumaSum * scale
            }
        }

        boxBlur5x5(sm, bufA)
        boxBlur5x5(bufA, bufB)

        var meanHp = 0f
        for (i in CIRC_INDICES.indices) {
            val idx = CIRC_INDICES[i]
            val v = sm[idx] - bufB[idx]
            hpOut[i] = v
            meanHp += v
            circSortBuf[i] = sm[idx]
        }
        meanHp /= CIRC_INDICES.size.toFloat()
        var varHp = 0f
        for (i in hpOut.indices) {
            val d = hpOut[i] - meanHp
            varHp += d * d
        }
        val stdHp = max(0.025f, kotlin.math.sqrt((varHp / hpOut.size.toFloat()).toDouble()).toFloat())
        for (i in hpOut.indices) {
            hpOut[i] /= stdHp
        }

        circSortBuf.sort()
        val p95Idx = ((circSortBuf.size - 1) * 95) / 100
        val p95 = max(0.35f, circSortBuf[p95Idx])
        for (i in CORE_INDICES.indices) {
            rawOut[i] = sm[CORE_INDICES[i]] / p95
        }
    }

    private fun boxBlur5x5(src: FloatArray, dst: FloatArray) {
        val inv25 = 1f / 25f
        for (y in 0 until G) {
            val yOff = y * G
            for (x in 0 until G) {
                var sum = 0f
                for (dy in -2..2) {
                    val cy = (y + dy).coerceIn(0, G - 1) * G
                    for (dx in -2..2) {
                        val cx = (x + dx).coerceIn(0, G - 1)
                        sum += src[cy + cx]
                    }
                }
                dst[yOff + x] = sum * inv25
            }
        }
    }
}
