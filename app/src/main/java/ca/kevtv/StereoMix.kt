package ca.kevtv

/** Android's conventional PCM channel order, with bounded gain to leave headroom. */
internal object StereoMix {
    private const val attenuated = .70710677f
    private val left = floatArrayOf(1f, 0f)
    private val right = floatArrayOf(0f, 1f)
    private val center = floatArrayOf(attenuated, attenuated)
    private val lfe = floatArrayOf(.25f, .25f)
    private val surroundLeft = floatArrayOf(attenuated, 0f)
    private val surroundRight = floatArrayOf(0f, attenuated)
    private val rearCenter = floatArrayOf(.5f, .5f)

    fun coefficients(channelCount: Int): FloatArray {
        val rows = when (channelCount) {
            1 -> arrayOf(floatArrayOf(1f, 1f))
            2 -> arrayOf(left, right)
            3 -> arrayOf(left, right, center)
            4 -> arrayOf(left, right, surroundLeft, surroundRight)
            5 -> arrayOf(left, right, center, surroundLeft, surroundRight)
            6 -> arrayOf(left, right, center, lfe, surroundLeft, surroundRight)
            7 -> arrayOf(left, right, center, lfe, rearCenter, surroundLeft, surroundRight)
            8 -> arrayOf(left, right, center, lfe, surroundLeft, surroundRight, surroundLeft, surroundRight)
            else -> error("Disposition audio non prise en charge.")
        }
        val peak = maxOf(1f, rows.fold(0f) { sum, row -> sum + row[0] }, rows.fold(0f) { sum, row -> sum + row[1] })
        return FloatArray(channelCount * 2) { i -> rows[i / 2][i % 2] / peak }
    }
}
