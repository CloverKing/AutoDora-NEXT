package com.autodora.next.vision

/**
 * Secondary wave transition detector based on uppercase enemy portrait/HP/banner region perceptual hash.
 * Covers stages that advance between waves directly without displaying a dimmed transition veil (`watchStatus == 3`).
 *
 * Requires:
 *  - Baseline locked over 2 consecutive settled battle frames (`hamming <= 6`)
 *  - Sustained scene shift (`hamming >= 18` vs baseline) confirmed across >= 4 consecutive stable frames
 */
class WaveTransitionDetector {

    private var baselineHash: Long? = null
    private var stableBaseTicks: Int = 0
    private var candidateHash: Long? = null
    private var candidateTicks: Int = 0

    @Synchronized
    fun reset() {
        baselineHash = null
        stableBaseTicks = 0
        candidateHash = null
        candidateTicks = 0
    }

    @Synchronized
    fun onBattleUpperZoneHash(
        upperHash: Long,
        hammingFn: (Long, Long) -> Int = { a, b -> java.lang.Long.bitCount(a xor b) }
    ): Boolean {
        val base = baselineHash
        if (base == null) {
            baselineHash = upperHash
            stableBaseTicks = 1
            candidateHash = null
            candidateTicks = 0
            return false
        }

        if (stableBaseTicks < BASELINE_LOCK_TICKS) {
            if (hammingFn(upperHash, base) <= SAME_SCENE_MAX_HAMMING) {
                stableBaseTicks++
                baselineHash = upperHash
            } else {
                baselineHash = upperHash
                stableBaseTicks = 1
            }
            return false
        }

        val distFromBase = hammingFn(upperHash, base)
        if (distFromBase < WAVE_CHANGE_MIN_HAMMING) {
            candidateHash = null
            candidateTicks = 0
            return false
        }

        val cand = candidateHash
        if (cand == null || hammingFn(upperHash, cand) > SAME_SCENE_MAX_HAMMING) {
            candidateHash = upperHash
            candidateTicks = 1
            return false
        }

        candidateTicks++
        candidateHash = upperHash
        if (candidateTicks >= CONFIRM_SHIFT_TICKS) {
            baselineHash = upperHash
            stableBaseTicks = BASELINE_LOCK_TICKS
            candidateHash = null
            candidateTicks = 0
            return true
        }
        return false
    }

    companion object {
        const val SAME_SCENE_MAX_HAMMING = 6
        const val WAVE_CHANGE_MIN_HAMMING = 18
        const val BASELINE_LOCK_TICKS = 2
        const val CONFIRM_SHIFT_TICKS = 4
    }
}
