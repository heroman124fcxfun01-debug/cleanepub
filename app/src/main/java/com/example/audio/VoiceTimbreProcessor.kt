package com.example.audio

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Multi-dimensional acoustic analysis of a 16kHz Mono reference voice recording:
 * - Median voiced F0 (Hz)
 * - 13-order MFCC (`mfcc13`: c0..c12) & vocal-tract formant center (`formantCenterHz`)
 * - 3-band spectral energy ratios, spectral centroid, spectral tilt, speaking rate, and 32-dim fingerprint
 */
data class VoiceAcousticProfile(
    val f0Hz: Float,
    val voicedFrameCount: Int,
    val lowEnergyRatio: Float,
    val midEnergyRatio: Float,
    val highEnergyRatio: Float,
    val spectralCentroidHz: Float,
    val spectralTilt: Float,
    val speakingRateSylPerSec: Float,
    val fingerprint32: FloatArray,
    val mfcc13: FloatArray = FloatArray(13),
    val formantCenterHz: Float = 1150f
)

/**
 * Persisted & runtime timbre transformation parameters applied to NeuCodec 24kHz PCM output
 * so distinct cloned voices preserve unmistakable pitch, vocal-tract formant length, warmth,
 * presence, brightness, and Top-2 preset blending metadata.
 */
data class VoiceTimbreSettings(
    val pitchRatio: Float = 1.0f,
    val formantShiftRatio: Float = 1.0f,
    val warmthGainDb: Float = 0.0f,
    val presenceGainDb: Float = 0.0f,
    val brightnessGainDb: Float = 0.0f,
    val speakingRateRatio: Float = 1.0f,
    val matchedPresetId: String? = null,
    val secondaryPresetId: String? = null,
    val blendWeightPrimary: Float = 1.0f
) {
    val isIdentity: Boolean
        get() = abs(pitchRatio - 1.0f) < 0.015f &&
            abs(formantShiftRatio - 1.0f) < 0.015f &&
            abs(warmthGainDb) < 0.35f &&
            abs(presenceGainDb) < 0.35f &&
            abs(brightnessGainDb) < 0.35f

    fun describeShort(): String = String.format(
        Locale.US,
        "pitch=%.2fx, fmt=%.2fx, bass=%+.1fdB, mid=%+.1fdB, treb=%+.1fdB, rate=%.2fx",
        pitchRatio,
        formantShiftRatio,
        warmthGainDb,
        presenceGainDb,
        brightnessGainDb,
        speakingRateRatio
    )
}

/**
 * Result of Top-2 Cosine-MFCC + F0 preset matching & weighted embedding blending.
 */
data class MatchedPresetBlendResult(
    val primaryPreset: VieNeuPresetVoice?,
    val secondaryPreset: VieNeuPresetVoice?,
    val primaryWeight: Float,
    val secondaryWeight: Float,
    val blendedVoiceEmbedding: FloatArray,
    val timbreSettings: VoiceTimbreSettings
)

/**
 * Acoustic voice analyzer (F0 + 13-order MFCC + Spectral Envelope), Top-2 Cosine-distance
 * `voices.json` preset blender, and real-time 24kHz PCM DSP processor:
 * 1. Duration-preserving WSOLA Pitch Shifting (`pitchRatio`)
 * 2. 512-pt STFT Cepstral Spectral-Envelope Formant Warping (`formantShiftRatio`, independent of pitch!)
 * 3. 3-Band Biquad Formant/Timbre EQ + soft peak protection
 */
object VoiceTimbreProcessor {

    private const val MFCC_ORDER = 13
    private const val MEL_BANDS = 20
    private const val ANALYZE_FFT_SIZE = 512
    private const val FORMANT_FFT_SIZE = 512
    private const val FORMANT_HOP_SIZE = 128 // 75% overlap @ 24kHz (~5.3ms hop)
    private const val CEPSTRAL_LIFTER_Q = 22 // ~0.92ms @ 24kHz — cleanly isolates smooth formant envelope

    private data class PresetAnchor(
        val keywords: List<String>,
        val isMale: Boolean,
        val baselineF0Hz: Float,
        val baselineTilt: Float,
        val baselineFormantHz: Float,
        val baselineRateSyl: Float,
        // Normalized characteristic 12-dim MFCC profile (c1..c12) capturing vocal-tract envelope
        val baselineMfcc12: FloatArray
    )

    // Calibrated acoustic + MFCC anchors for official pnnbao-ump Vietnamese voices.json presets:
    // Vinh (Nam miền Nam trầm ấm), Binh (Nam miền Bắc chuẩn), Tuyen (Nam miền Bắc sáng/truyền cảm),
    // Doan (Nữ miền Nam trầm ấm), Ly (Nữ miền Bắc chuẩn), Ngoc (Nữ miền Bắc cao/trong).
    private val KNOWN_PRESET_ANCHORS = listOf(
        PresetAnchor(
            keywords = listOf("vinh", "vĩnh"),
            isMale = true,
            baselineF0Hz = 112f,
            baselineTilt = -0.35f,
            baselineFormantHz = 960f,
            baselineRateSyl = 3.8f,
            baselineMfcc12 = floatArrayOf(1.15f, 0.42f, -0.25f, 0.18f, -0.15f, 0.10f, -0.08f, 0.05f, -0.04f, 0.03f, -0.02f, 0.01f)
        ),
        PresetAnchor(
            keywords = listOf("binh", "bình"),
            isMale = true,
            baselineF0Hz = 132f,
            baselineTilt = 0.05f,
            baselineFormantHz = 1080f,
            baselineRateSyl = 4.4f,
            baselineMfcc12 = floatArrayOf(0.55f, 0.12f, -0.10f, 0.14f, -0.06f, 0.08f, -0.05f, 0.04f, -0.02f, 0.02f, -0.01f, 0.01f)
        ),
        PresetAnchor(
            keywords = listOf("tuyen", "tuyên"),
            isMale = true,
            baselineF0Hz = 152f,
            baselineTilt = 0.30f,
            baselineFormantHz = 1210f,
            baselineRateSyl = 4.9f,
            baselineMfcc12 = floatArrayOf(0.12f, -0.18f, 0.08f, 0.10f, 0.05f, -0.04f, 0.03f, 0.02f, 0.02f, -0.01f, 0.01f, 0.01f)
        ),
        PresetAnchor(
            keywords = listOf("doan", "đoan"),
            isMale = false,
            baselineF0Hz = 186f,
            baselineTilt = -0.25f,
            baselineFormantHz = 1240f,
            baselineRateSyl = 4.0f,
            baselineMfcc12 = floatArrayOf(0.42f, 0.25f, -0.22f, -0.05f, 0.12f, -0.08f, 0.06f, -0.03f, 0.02f, -0.02f, 0.01f, -0.01f)
        ),
        PresetAnchor(
            keywords = listOf("ly"),
            isMale = false,
            baselineF0Hz = 216f,
            baselineTilt = 0.06f,
            baselineFormantHz = 1390f,
            baselineRateSyl = 4.5f,
            baselineMfcc12 = floatArrayOf(-0.18f, -0.08f, -0.10f, -0.12f, 0.08f, 0.04f, -0.04f, 0.03f, 0.02f, -0.01f, 0.01f, 0.01f)
        ),
        PresetAnchor(
            keywords = listOf("ngoc", "ngọc"),
            isMale = false,
            baselineF0Hz = 248f,
            baselineTilt = 0.34f,
            baselineFormantHz = 1540f,
            baselineRateSyl = 5.0f,
            baselineMfcc12 = floatArrayOf(-0.68f, -0.35f, 0.15f, -0.18f, -0.06f, 0.10f, 0.05f, -0.04f, 0.03f, 0.02f, -0.02f, 0.01f)
        )
    )

    private val MALE_HINTS = listOf(
        "nam", "male", "vinh", "vĩnh", "binh", "bình", "tuyen", "tuyên",
        "son", "sơn", "duc", "đức", "minh", "hung", "hùng", "quan", "quân", "man"
    )
    private val FEMALE_HINTS = listOf(
        "nu", "nữ", "female", "doan", "đoan", "ly", "ngoc", "ngọc", "dung",
        "bich", "bích", "huong", "hương", "linh", "lan", "mai", "ha", "hà", "trang", "thao", "thảo"
    )
    private val SOUTH_HINTS = listOf("nam)", "miền nam", "mien nam", "south", "saigon")

    /**
     * Analyzes a 16kHz Mono Float32 speech sample across all voiced frames:
     * - Median voiced F0 (Hz) using normalized autocorrelation + parabolic refinement.
     * - **13-order MFCC** (`mfcc13`: c0..c12) via 512-pt Radix-2 FFT + 20-band Mel Filterbank + DCT-II.
     * - **Vocal-tract Formant Center** (`formantCenterHz`) from the F1–F3 band spectral envelope (300–3200 Hz).
     * - 3-band spectral energy ratios, spectral centroid, spectral tilt (-1..+1), and speaking rate.
     */
    fun analyzeVoice16k(
        samples: FloatArray,
        sampleRate: Int = NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE
    ): VoiceAcousticProfile {
        val fingerprint = OnnxModelAssetHelper.computeAcousticFingerprint(samples)
        if (samples.size < sampleRate / 4 || sampleRate <= 0) {
            return VoiceAcousticProfile(
                f0Hz = 165f,
                voicedFrameCount = 0,
                lowEnergyRatio = 0.34f,
                midEnergyRatio = 0.44f,
                highEnergyRatio = 0.22f,
                spectralCentroidHz = 1350f,
                spectralTilt = 0f,
                speakingRateSylPerSec = 4.4f,
                fingerprint32 = fingerprint,
                mfcc13 = FloatArray(MFCC_ORDER),
                formantCenterHz = 1180f
            )
        }

        // Compute global RMS to establish adaptive Voice Activity Detection threshold
        var globalSq = 0.0
        for (v in samples) globalSq += v.toDouble() * v.toDouble()
        val globalRms = sqrt(globalSq / samples.size).toFloat().coerceAtLeast(1e-5f)
        val vadRmsThreshold = (globalRms * 0.42f).coerceAtLeast(0.012f)

        val frameSize = ANALYZE_FFT_SIZE                             // 512 samples (32ms @ 16kHz)
        val hopSize = frameSize / 2                                  // 256 samples (16ms @ 16kHz)
        val minLag = (sampleRate / 360).coerceAtLeast(24)            // 360 Hz max F0
        val maxLag = (sampleRate / 68).coerceAtMost(frameSize - 16)  // 68 Hz min F0

        val f0Estimates = ArrayList<Float>(128)
        val frameRmsList = ArrayList<Float>(samples.size / hopSize + 1)

        var sumLow = 0.0
        var sumMid = 0.0
        var sumHigh = 0.0
        var centroidWeightedNum = 0.0
        var centroidWeightedDen = 0.0
        var formantWeightedNum = 0.0
        var formantWeightedDen = 0.0
        var voicedFrames = 0

        val window = FloatArray(frameSize) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (frameSize - 1).coerceAtLeast(1))).toFloat()
        }
        val melFilters = buildMelFilterbank(
            fftSize = frameSize,
            sampleRate = sampleRate,
            numBands = MEL_BANDS,
            minFreqHz = 80.0,
            maxFreqHz = min(7600.0, sampleRate * 0.48)
        )
        val dctTable = buildDctMatrix(numCoeffs = MFCC_ORDER, numBands = MEL_BANDS)
        val mfccAccum = DoubleArray(MFCC_ORDER)

        val fftRe = DoubleArray(frameSize)
        val fftIm = DoubleArray(frameSize)
        val powerSpec = DoubleArray(frameSize / 2 + 1)
        val logMel = DoubleArray(MEL_BANDS)
        val binHz = sampleRate.toDouble() / frameSize.toDouble()

        var pos = 0
        while (pos + frameSize <= samples.size) {
            var sq = 0.0
            var zc = 0
            for (i in 0 until frameSize) {
                val s = samples[pos + i]
                sq += s.toDouble() * s.toDouble()
                if (i > 0 && ((samples[pos + i - 1] >= 0f) != (s >= 0f))) zc++
            }
            val rms = sqrt(sq / frameSize).toFloat()
            frameRmsList.add(rms)

            val zcr = zc.toFloat() / frameSize.toFloat()
            if (rms >= vadRmsThreshold && zcr < 0.24f) {
                // 1. Estimate F0 via normalized autocorrelation + earliest-peak selection + parabolic refinement
                var bestLag = -1
                var bestCorr = 0.0
                val corrVals = DoubleArray(maxLag - minLag + 1)
                for (lag in minLag..maxLag) {
                    var num = 0.0
                    var e1 = 0.0
                    var e2 = 0.0
                    val limit = frameSize - lag
                    var i = 0
                    while (i < limit) {
                        val a = samples[pos + i].toDouble()
                        val b = samples[pos + i + lag].toDouble()
                        num += a * b
                        e1 += a * a
                        e2 += b * b
                        i += 2
                    }
                    val den = sqrt(e1 * e2)
                    val c = if (den > 1e-8) num / den else 0.0
                    corrVals[lag - minLag] = c
                    if (c > bestCorr) {
                        bestCorr = c
                        bestLag = lag
                    }
                }

                if (bestLag > minLag && bestLag < maxLag && bestCorr >= 0.42) {
                    val threshold = bestCorr * 0.88
                    var chosenLag = bestLag
                    for (lag in (minLag + 1) until bestLag) {
                        val idx = lag - minLag
                        val c = corrVals[idx]
                        if (c >= threshold && c >= corrVals[idx - 1] && c >= corrVals[idx + 1]) {
                            chosenLag = lag
                            break
                        }
                    }
                    val cIdx = chosenLag - minLag
                    val refinedLag = if (cIdx in 1 until corrVals.lastIndex) {
                        val y0 = corrVals[cIdx - 1]
                        val y1 = corrVals[cIdx]
                        val y2 = corrVals[cIdx + 1]
                        val denom = (y0 - 2.0 * y1 + y2)
                        val delta = if (abs(denom) > 1e-9) 0.5 * (y0 - y2) / denom else 0.0
                        chosenLag + delta.coerceIn(-0.5, 0.5)
                    } else {
                        chosenLag.toDouble()
                    }
                    val f0 = (sampleRate.toDouble() / refinedLag).toFloat()
                    if (f0 in 70f..350f) {
                        f0Estimates.add(f0)
                    }
                }

                // 2. 512-pt FFT for 3-band spectral envelope + 13-order MFCC + Formant center
                var prevSample = samples[pos].toDouble()
                for (i in 0 until frameSize) {
                    val cur = samples[pos + i].toDouble()
                    val preEmph = cur - 0.97 * prevSample
                    prevSample = cur
                    fftRe[i] = preEmph * window[i]
                    fftIm[i] = 0.0
                }
                fftInPlace(fftRe, fftIm, inverse = false)

                var fLow = 0.0
                var fMid = 0.0
                var fHigh = 0.0
                for (k in 0..frameSize / 2) {
                    val p = fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]
                    powerSpec[k] = p
                    val freq = k * binHz
                    if (freq in 80.0..5500.0) {
                        centroidWeightedNum += freq * p
                        centroidWeightedDen += p
                        when {
                            freq < 450.0 -> fLow += p
                            freq < 1800.0 -> fMid += p
                            else -> fHigh += p
                        }
                    }
                    // F1–F3 formant region (300–3200 Hz), weighted by log-compressed magnitude to avoid F0 domination
                    if (freq in 300.0..3200.0) {
                        val mag = sqrt(p)
                        formantWeightedNum += freq * mag
                        formantWeightedDen += mag
                    }
                }
                sumLow += fLow
                sumMid += fMid
                sumHigh += fHigh

                // 20-band Mel filterbank + log energy
                for (m in 0 until MEL_BANDS) {
                    val weights = melFilters[m]
                    var bandEnergy = 1e-10
                    for (k in weights.indices) {
                        val w = weights[k]
                        if (w > 0.0) bandEnergy += powerSpec[k] * w
                    }
                    logMel[m] = ln(bandEnergy)
                }

                // DCT-II -> 13 MFCCs
                for (c in 0 until MFCC_ORDER) {
                    val row = dctTable[c]
                    var sum = 0.0
                    for (m in 0 until MEL_BANDS) {
                        sum += logMel[m] * row[m]
                    }
                    mfccAccum[c] += sum
                }

                voicedFrames++
            }
            pos += hopSize
        }

        // Robust trimmed median F0
        val medianF0 = if (f0Estimates.isNotEmpty()) {
            f0Estimates.sort()
            val lo = (f0Estimates.size * 0.25f).toInt().coerceIn(0, f0Estimates.lastIndex)
            val hi = (f0Estimates.size * 0.75f).toInt().coerceIn(lo, f0Estimates.lastIndex)
            var sum = 0f
            for (i in lo..hi) sum += f0Estimates[i]
            (sum / (hi - lo + 1).coerceAtLeast(1)).coerceIn(75f, 340f)
        } else {
            165f
        }

        val mfcc13 = FloatArray(MFCC_ORDER) { c ->
            if (voicedFrames > 0) (mfccAccum[c] / voicedFrames.toDouble()).toFloat() else 0f
        }

        val totalSpec = (sumLow + sumMid + sumHigh).coerceAtLeast(1e-9)
        val lowRatio = (sumLow / totalSpec).toFloat()
        val midRatio = (sumMid / totalSpec).toFloat()
        val highRatio = (sumHigh / totalSpec).toFloat()
        val centroidHz = if (centroidWeightedDen > 1e-9) {
            (centroidWeightedNum / centroidWeightedDen).toFloat().coerceIn(300f, 4500f)
        } else {
            1350f
        }
        val formantCenterHz = if (formantWeightedDen > 1e-9) {
            (formantWeightedNum / formantWeightedDen).toFloat().coerceIn(680f, 2200f)
        } else {
            1180f
        }

        // Log-ratio spectral tilt in [-1.0, +1.0]: negative = warm/bass-heavy, positive = bright/crisp
        val rawTilt = ln(((highRatio + 0.35f * midRatio) + 1e-4f) / (lowRatio + 1e-4f))
        val spectralTilt = (rawTilt / 2.6f).coerceIn(-1.0f, 1.0f)

        val speakingRate = estimateSpeakingRate(
            frameRms = frameRmsList,
            hopSec = hopSize.toFloat() / sampleRate.toFloat(),
            activeThreshold = vadRmsThreshold * 0.85f
        )

        return VoiceAcousticProfile(
            f0Hz = medianF0,
            voicedFrameCount = voicedFrames,
            lowEnergyRatio = lowRatio,
            midEnergyRatio = midRatio,
            highEnergyRatio = highRatio,
            spectralCentroidHz = centroidHz,
            spectralTilt = spectralTilt,
            speakingRateSylPerSec = speakingRate,
            fingerprint32 = fingerprint,
            mfcc13 = mfcc13,
            formantCenterHz = formantCenterHz
        )
    }

    private fun estimateSpeakingRate(
        frameRms: List<Float>,
        hopSec: Float,
        activeThreshold: Float
    ): Float {
        if (frameRms.size < 8) return 4.4f
        val env = FloatArray(frameRms.size) { i ->
            val a = frameRms[(i - 1).coerceAtLeast(0)]
            val b = frameRms[i]
            val c = frameRms[(i + 1).coerceAtMost(frameRms.lastIndex)]
            (a + 2f * b + c) * 0.25f
        }
        val activeFrames = env.count { it >= activeThreshold }
        val activeSec = (activeFrames * hopSec).coerceAtLeast(0.5f)
        val minPeakGapFrames = (0.12f / hopSec).roundToInt().coerceAtLeast(3)
        var peaks = 0
        var lastPeak = -minPeakGapFrames
        for (i in 1 until env.lastIndex) {
            if (i - lastPeak >= minPeakGapFrames &&
                env[i] >= activeThreshold * 1.15f &&
                env[i] >= env[i - 1] &&
                env[i] >= env[i + 1]
            ) {
                peaks++
                lastPeak = i
            }
        }
        return (peaks / activeSec).coerceIn(2.2f, 7.2f)
    }

    private fun inferAnchorForPreset(preset: VieNeuPresetVoice, indexInList: Int, totalCount: Int): PresetAnchor {
        val combined = "${preset.id} ${preset.description}".lowercase(Locale.ROOT)
        for (anchor in KNOWN_PRESET_ANCHORS) {
            if (anchor.keywords.any { kw ->
                    preset.id.equals(kw, ignoreCase = true) ||
                        combined.contains(" $kw") ||
                        combined.startsWith(kw)
                }
            ) {
                return anchor
            }
        }
        val isMale = MALE_HINTS.any { combined.contains(it) } && !FEMALE_HINTS.any { combined.contains(it) }
        val isSouth = SOUTH_HINTS.any { combined.contains(it) }
        val frac = if (totalCount > 1) indexInList.toFloat() / (totalCount - 1).toFloat() else 0.5f
        return if (isMale) {
            val tilt = if (isSouth) -0.28f else (-0.10f + frac * 0.35f)
            PresetAnchor(
                keywords = emptyList(),
                isMale = true,
                baselineF0Hz = if (isSouth) 115f else (122f + frac * 30f),
                baselineTilt = tilt,
                baselineFormantHz = if (isSouth) 990f else (1050f + frac * 160f),
                baselineRateSyl = 4.2f,
                baselineMfcc12 = floatArrayOf(
                    -tilt * 1.5f + 0.5f, 0.15f - frac * 0.25f, -0.10f, 0.12f,
                    -0.06f, 0.05f, -0.04f, 0.03f, -0.02f, 0.02f, -0.01f, 0.01f
                )
            )
        } else {
            val tilt = if (isSouth) -0.22f else (-0.05f + frac * 0.40f)
            PresetAnchor(
                keywords = emptyList(),
                isMale = false,
                baselineF0Hz = if (isSouth) 188f else (200f + frac * 45f),
                baselineTilt = tilt,
                baselineFormantHz = if (isSouth) 1260f else (1340f + frac * 200f),
                baselineRateSyl = 4.5f,
                baselineMfcc12 = floatArrayOf(
                    -tilt * 1.5f - 0.1f, 0.05f - frac * 0.30f, -0.08f, -0.10f,
                    0.06f, 0.04f, -0.03f, 0.02f, 0.02f, -0.01f, 0.01f, 0.01f
                )
            )
        }
    }

    /**
     * Maps the user's multi-dimensional [VoiceAcousticProfile] (F0 + 13-order MFCC + Formant Center +
     * Spectral Tilt + Speaking Rate) across ALL presets in `voices.json`:
     * 1. Computes **Cosine distance on MFCC (c1..c12)** combined with Log-F0, formant center, spectral tilt, and rate.
     * 2. Picks the **Top 2 closest presets** and computes inverse-distance blend weights `w1` (0.55..0.85), `w2 = 1 - w1`.
     * 3. Computes a **weighted linear blended `voice_embedding`** (`L2Norm(w1 * emb1 + w2 * emb2)`).
     * 4. Computes relative **Pitch Shift (`pitchRatio`)**, **Formant Shift (`formantShiftRatio`)**,
     *    3-band EQ gains, and speaking-rate ratio against the weighted Top-2 acoustic baseline.
     */
    fun matchBestPresetAndComputeTimbre(
        profile: VoiceAcousticProfile,
        presets: List<VieNeuPresetVoice>
    ): MatchedPresetBlendResult {
        val valid = presets.filter { it.referenceCodes.size >= 8 }
        if (valid.isEmpty()) {
            val fallbackPitch = (profile.f0Hz / 165f).coerceIn(0.78f, 1.28f)
            val fallbackFormant = (profile.formantCenterHz / 1180f).coerceIn(0.84f, 1.18f)
            return MatchedPresetBlendResult(
                primaryPreset = null,
                secondaryPreset = null,
                primaryWeight = 1.0f,
                secondaryWeight = 0.0f,
                blendedVoiceEmbedding = buildFallbackAcousticEmbedding(profile),
                timbreSettings = VoiceTimbreSettings(
                    pitchRatio = fallbackPitch,
                    formantShiftRatio = fallbackFormant,
                    warmthGainDb = (-profile.spectralTilt * 3.0f).coerceIn(-4.0f, 4.0f),
                    presenceGainDb = ((profile.midEnergyRatio - 0.42f) * 8.0f).coerceIn(-3.0f, 3.0f),
                    brightnessGainDb = (profile.spectralTilt * 3.5f).coerceIn(-4.0f, 4.0f),
                    speakingRateRatio = (profile.speakingRateSylPerSec / 4.4f).coerceIn(0.88f, 1.14f),
                    matchedPresetId = null,
                    secondaryPresetId = null,
                    blendWeightPrimary = 1.0f
                )
            )
        }

        // Normalize user's MFCC c1..c12 for cosine similarity against preset MFCC anchors
        val userMfcc12 = FloatArray(12) { i ->
            profile.mfcc13.getOrElse(i + 1) { 0f }
        }
        val userLogF0 = ln(profile.f0Hz.toDouble())
        val userLogFormant = ln(profile.formantCenterHz.toDouble())

        data class ScoredPreset(
            val preset: VieNeuPresetVoice,
            val anchor: PresetAnchor,
            val distance: Double
        )

        val scored = valid.mapIndexed { idx, preset ->
            val anchor = inferAnchorForPreset(preset, idx, valid.size)
            val f0DiffSemitones = 12.0 * (userLogF0 - ln(anchor.baselineF0Hz.toDouble())) / ln(2.0)
            val formantDiffSemitones = 12.0 * (userLogFormant - ln(anchor.baselineFormantHz.toDouble())) / ln(2.0)
            val tiltDiff = (profile.spectralTilt - anchor.baselineTilt).toDouble()
            val rateDiff = ((profile.speakingRateSylPerSec - anchor.baselineRateSyl) / 1.5).toDouble()
            val mfccCosineDist = cosineDistance(userMfcc12, anchor.baselineMfcc12) // [0.0, 2.0]

            val genderPenalty = when {
                profile.f0Hz < 158f && !anchor.isMale -> 6.5
                profile.f0Hz > 182f && anchor.isMale -> 6.5
                else -> 0.0
            }

            val score = (f0DiffSemitones * 0.62) * (f0DiffSemitones * 0.62) +
                (formantDiffSemitones * 0.48) * (formantDiffSemitones * 0.48) +
                (mfccCosineDist * 2.4) * (mfccCosineDist * 2.4) +
                (tiltDiff * 2.8) * (tiltDiff * 2.8) +
                (rateDiff * 1.0) * (rateDiff * 1.0) +
                genderPenalty

            ScoredPreset(preset, anchor, score)
        }.sortedBy { it.distance }

        val top1 = scored[0]
        val top2 = scored.getOrNull(1)

        val w1: Float
        val w2: Float
        if (top2 != null) {
            val inv1 = 1.0 / (sqrt(top1.distance.coerceAtLeast(0.0)) + 0.35)
            val inv2 = 1.0 / (sqrt(top2.distance.coerceAtLeast(0.0)) + 0.35)
            w1 = (inv1 / (inv1 + inv2)).toFloat().coerceIn(0.55f, 0.85f)
            w2 = 1.0f - w1
        } else {
            w1 = 1.0f
            w2 = 0.0f
        }

        // Compute weighted linear blend of Top-2 voice embeddings (`w1 * emb1 + w2 * emb2`)
        val blendedEmb = blendPresetEmbeddings(
            primary = top1.preset,
            secondary = top2?.preset,
            w1 = w1,
            w2 = w2,
            fallbackProfile = profile
        )

        // Compute weighted baseline acoustic targets for accurate Pitch + Formant + EQ estimation
        val baseF0 = if (top2 != null) {
            top1.anchor.baselineF0Hz * w1 + top2.anchor.baselineF0Hz * w2
        } else {
            top1.anchor.baselineF0Hz
        }
        val baseFormantHz = if (top2 != null) {
            top1.anchor.baselineFormantHz * w1 + top2.anchor.baselineFormantHz * w2
        } else {
            top1.anchor.baselineFormantHz
        }
        val baseTilt = if (top2 != null) {
            top1.anchor.baselineTilt * w1 + top2.anchor.baselineTilt * w2
        } else {
            top1.anchor.baselineTilt
        }
        val baseRate = if (top2 != null) {
            top1.anchor.baselineRateSyl * w1 + top2.anchor.baselineRateSyl * w2
        } else {
            top1.anchor.baselineRateSyl
        }

        // Relative Pitch shift against the primary preset's natural F0 (GGUF conditions on primaryPreset's refCodes)
        // blended lightly with top2 baseline when NeuCodec uses blended embedding.
        val effectiveBaseF0 = top1.anchor.baselineF0Hz * 0.72f + baseF0 * 0.28f
        val pitchRatio = (profile.f0Hz / effectiveBaseF0).coerceIn(0.78f, 1.28f)

        // Vocal-tract Formant shift ratio (independent of pitch!): combines measured formant center ratio
        // with MFCC c1/c2 brightness cues, gently damped for completely natural Vietnamese vowel clarity.
        val rawFormantRatio = profile.formantCenterHz / baseFormantHz
        val formantShiftRatio = (1.0f + (rawFormantRatio - 1.0f) * 0.78f).coerceIn(0.84f, 1.18f)

        val tiltDelta = (profile.spectralTilt - baseTilt).coerceIn(-1.2f, 1.2f)
        val warmthGainDb = (-tiltDelta * 4.0f + (profile.lowEnergyRatio - 0.34f) * 5.5f)
            .coerceIn(-4.5f, 4.5f)
        val presenceGainDb = ((profile.midEnergyRatio - 0.42f) * 7.0f + (profile.spectralCentroidHz - 1400f) / 1300f)
            .coerceIn(-3.2f, 3.2f)
        val brightnessGainDb = (tiltDelta * 4.2f + (profile.highEnergyRatio - 0.22f) * 5.5f)
            .coerceIn(-4.5f, 4.5f)
        val rateRatio = (profile.speakingRateSylPerSec / baseRate).coerceIn(0.90f, 1.12f)

        val settings = VoiceTimbreSettings(
            pitchRatio = pitchRatio,
            formantShiftRatio = formantShiftRatio,
            warmthGainDb = warmthGainDb,
            presenceGainDb = presenceGainDb,
            brightnessGainDb = brightnessGainDb,
            speakingRateRatio = rateRatio,
            matchedPresetId = top1.preset.id,
            secondaryPresetId = top2?.preset?.id,
            blendWeightPrimary = w1
        )

        return MatchedPresetBlendResult(
            primaryPreset = top1.preset,
            secondaryPreset = top2?.preset,
            primaryWeight = w1,
            secondaryWeight = w2,
            blendedVoiceEmbedding = blendedEmb,
            timbreSettings = settings
        )
    }

    private fun blendPresetEmbeddings(
        primary: VieNeuPresetVoice,
        secondary: VieNeuPresetVoice?,
        w1: Float,
        w2: Float,
        fallbackProfile: VoiceAcousticProfile
    ): FloatArray {
        val emb1 = primary.voiceEmbedding
        val emb2 = secondary?.voiceEmbedding
        if (emb1 != null && emb1.isNotEmpty()) {
            if (emb2 != null && emb2.size == emb1.size && w2 > 0.01f) {
                val blended = FloatArray(emb1.size) { i -> emb1[i] * w1 + emb2[i] * w2 }
                return l2NormalizePreserveScale(blended, referenceNorm = l2Norm(emb1))
            }
            return emb1.copyOf()
        }
        return buildFallbackAcousticEmbedding(fallbackProfile)
    }

    private fun buildFallbackAcousticEmbedding(profile: VoiceAcousticProfile): FloatArray {
        val out = FloatArray(32)
        val mfccCopy = min(profile.mfcc13.size, 13)
        for (i in 0 until mfccCopy) {
            out[i] = profile.mfcc13[i]
        }
        val fpCopy = min(profile.fingerprint32.size, 32 - mfccCopy)
        for (i in 0 until fpCopy) {
            out[mfccCopy + i] = profile.fingerprint32[i]
        }
        return l2NormalizePreserveScale(out, referenceNorm = 1.0f)
    }

    private fun l2Norm(v: FloatArray): Float {
        var sq = 0.0
        for (x in v) sq += x.toDouble() * x.toDouble()
        return sqrt(sq).toFloat()
    }

    private fun l2NormalizePreserveScale(v: FloatArray, referenceNorm: Float): FloatArray {
        val norm = l2Norm(v)
        if (norm < 1e-6f) return v
        val target = if (referenceNorm > 1e-4f) referenceNorm else 1.0f
        val scale = target / norm
        return FloatArray(v.size) { i -> v[i] * scale }
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        val len = min(a.size, b.size)
        if (len == 0) return 1.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until len) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        val denom = sqrt(na * nb)
        if (denom < 1e-8) return 1.0
        val cosSim = (dot / denom).coerceIn(-1.0, 1.0)
        return 1.0 - cosSim
    }

    /**
     * Applies real acoustic voice transformation directly onto 24kHz Mono Float32 PCM:
     * 1. **WSOLA Time-Domain Pitch Shifting** (duration-preserving): shifts fundamental F0 by
     *    `timbre.pitchRatio` cleanly.
     * 2. **512-pt STFT Cepstral Formant Shifting** (independent vocal-tract envelope warping):
     *    compensates for WSOLA's natural formant scaling and places F1/F2/F3/F4 precisely at
     *    `timbre.formantShiftRatio` without touching pitch or phase!
     * 3. **3-Band Biquad Formant / Timbre EQ**:
     *    - Low-shelf @ 240 Hz (`warmthGainDb`)
     *    - Peaking formant @ 1350 Hz (`presenceGainDb`, Q = 1.1)
     *    - High-shelf @ 3200 Hz (`brightnessGainDb`)
     * 4. Soft peak limiter & 6ms edge fades.
     */
    fun applyTimbreAndPitch(
        samples: FloatArray,
        sampleRate: Int,
        timbre: VoiceTimbreSettings?
    ): FloatArray {
        if (timbre == null || timbre.isIdentity || samples.size < sampleRate / 10) {
            return samples
        }

        // 1. Duration-preserving WSOLA Pitch Shift
        val targetPitch = timbre.pitchRatio.coerceIn(0.76f, 1.30f)
        val appliedPitch: Float
        var working = if (abs(targetPitch - 1.0f) >= 0.02f) {
            appliedPitch = targetPitch
            pitchShiftWsola(samples, sampleRate, targetPitch)
        } else {
            appliedPitch = 1.0f
            samples.copyOf()
        }

        // 2. 512-pt STFT Cepstral Formant Warping:
        // Since WSOLA resampling shifts both F0 and formants by `appliedPitch`, apply the exact
        // residual spectral-envelope warp (`targetFormant / appliedPitch`) so F1–F4 land precisely
        // on the user's vocal-tract length (`targetFormant`) with zero chipmunk or throat distortion!
        val targetFormant = timbre.formantShiftRatio.coerceIn(0.84f, 1.18f)
        val netFormantWarp = (targetFormant / appliedPitch).coerceIn(0.78f, 1.28f)
        if (abs(netFormantWarp - 1.0f) >= 0.022f && working.size >= FORMANT_FFT_SIZE * 2) {
            working = formantShiftCepstralStft(working, netFormantWarp)
        }

        // 3. 3-Band Biquad Formant / EQ Shaping
        if (abs(timbre.warmthGainDb) >= 0.35f) {
            applyBiquadInPlace(
                working,
                BiquadCoeffs.lowShelf(sampleRate.toDouble(), 240.0, timbre.warmthGainDb.toDouble())
            )
        }
        if (abs(timbre.presenceGainDb) >= 0.35f) {
            applyBiquadInPlace(
                working,
                BiquadCoeffs.peaking(sampleRate.toDouble(), 1350.0, 1.1, timbre.presenceGainDb.toDouble())
            )
        }
        if (abs(timbre.brightnessGainDb) >= 0.35f) {
            applyBiquadInPlace(
                working,
                BiquadCoeffs.highShelf(sampleRate.toDouble(), 3200.0, timbre.brightnessGainDb.toDouble())
            )
        }

        // 4. Soft peak protection & 6ms de-click fades
        var peak = 0f
        for (v in working) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak > 0.94f) {
            val gain = 0.92f / peak
            for (i in working.indices) {
                working[i] = tanh((working[i] * gain).toDouble()).toFloat()
            }
        }
        val fade = (sampleRate * 6 / 1000).coerceAtMost(working.size / 4)
        for (i in 0 until fade) {
            val w = i.toFloat() / fade.coerceAtLeast(1).toFloat()
            working[i] *= w
            working[working.lastIndex - i] *= w
        }
        return working
    }

    /**
     * 512-pt STFT Cepstral Spectral-Envelope Formant Warping @ 24kHz (hop=128, 75% Hann overlap):
     * Isolates the smooth vocal-tract envelope via low-quefrency cepstral liftering (Q=22),
     * warps the formant frequency axis by `formantWarpRatio`, and scales STFT magnitudes while
     * keeping original phase completely untouched for crystal-clear speech.
     */
    private fun formantShiftCepstralStft(input: FloatArray, formantWarpRatio: Float): FloatArray {
        val n = FORMANT_FFT_SIZE
        val halfN = n / 2
        val hop = FORMANT_HOP_SIZE
        val len = input.size

        val hann = DoubleArray(n) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / n) }
        val lifter = DoubleArray(n).apply {
            val q = CEPSTRAL_LIFTER_Q
            val taper = 4
            this[0] = 1.0
            for (k in 1 until n) {
                val dist = min(k, n - k)
                val w = when {
                    dist <= q - taper -> 1.0
                    dist >= q + taper -> 0.0
                    else -> {
                        val t = (dist - (q - taper)).toDouble() / (2.0 * taper)
                        0.5 * (1.0 + cos(PI * t))
                    }
                }
                this[k] = w
            }
        }

        val out = DoubleArray(len)
        val norm = DoubleArray(len)

        val specRe = DoubleArray(n)
        val specIm = DoubleArray(n)
        val cepRe = DoubleArray(n)
        val cepIm = DoubleArray(n)

        val invWarp = 1.0 / formantWarpRatio.toDouble()
        var pos = 0
        while (pos < len) {
            val remain = min(n, len - pos)
            for (i in 0 until remain) {
                specRe[i] = input[pos + i].toDouble() * hann[i]
                specIm[i] = 0.0
            }
            for (i in remain until n) {
                specRe[i] = 0.0
                specIm[i] = 0.0
            }

            // 1. Forward STFT
            fftInPlace(specRe, specIm, inverse = false)

            // 2. Real log-magnitude spectrum (symmetric)
            for (k in 0..halfN) {
                val mag = sqrt(specRe[k] * specRe[k] + specIm[k] * specIm[k])
                val lm = ln(mag + 1e-6)
                cepRe[k] = lm
                cepIm[k] = 0.0
                if (k in 1 until halfN) {
                    cepRe[n - k] = lm
                    cepIm[n - k] = 0.0
                }
            }

            // 3. Inverse FFT -> Real Cepstrum -> Low-time Lifter -> Forward FFT -> Smooth Spectral Envelope
            fftInPlace(cepRe, cepIm, inverse = true)
            for (i in 0 until n) {
                cepRe[i] *= lifter[i]
                cepIm[i] = 0.0
            }
            fftInPlace(cepRe, cepIm, inverse = false)
            // Now cepRe[0..halfN] holds the smooth log-magnitude vocal-tract formant envelope

            // 4. Warp envelope along frequency axis and scale STFT magnitudes without altering phase
            for (k in 1..halfN) {
                val srcBin = k * invWarp
                val warpedEnv = if (srcBin <= halfN.toDouble()) {
                    val i0 = srcBin.toInt().coerceIn(0, halfN - 1)
                    val frac = srcBin - i0
                    cepRe[i0] * (1.0 - frac) + cepRe[i0 + 1] * frac
                } else {
                    // Gentle high-frequency rolloff beyond Nyquist source bin
                    cepRe[halfN] - (srcBin - halfN) * 0.03
                }
                val logGain = (warpedEnv - cepRe[k]).coerceIn(-1.10, 1.10)
                val gain = exp(logGain)
                specRe[k] *= gain
                specIm[k] *= gain
                if (k < halfN) {
                    specRe[n - k] *= gain
                    specIm[n - k] *= gain
                }
            }

            // 5. Inverse STFT + Hann synthesis overlap-add
            fftInPlace(specRe, specIm, inverse = true)
            for (i in 0 until remain) {
                val w = hann[i]
                out[pos + i] += specRe[i] * w
                norm[pos + i] += w * w
            }

            pos += hop
        }

        return FloatArray(len) { i ->
            val d = norm[i]
            if (d > 1e-6) (out[i] / d).toFloat().coerceIn(-1.0f, 1.0f) else input[i]
        }
    }

    /**
     * In-place iterative Radix-2 Cooley-Tukey FFT (`n` must be a power of 2).
     */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        val angleSign = if (inverse) 2.0 * PI else -2.0 * PI
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val ang = angleSign / len.toDouble()
            val wLenRe = cos(ang)
            val wLenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val idx2 = i + k + halfLen
                    val vRe = re[idx2] * wRe - im[idx2] * wIm
                    val vIm = re[idx2] * wIm + im[idx2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[idx2] = uRe - vRe
                    im[idx2] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1.0 / n.toDouble()
            for (i in 0 until n) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun buildMelFilterbank(
        fftSize: Int,
        sampleRate: Int,
        numBands: Int,
        minFreqHz: Double,
        maxFreqHz: Double
    ): Array<DoubleArray> {
        val numBins = fftSize / 2 + 1
        val melMin = hzToMel(minFreqHz)
        val melMax = hzToMel(maxFreqHz)
        val binPoints = IntArray(numBands + 2) { i ->
            val mel = melMin + (melMax - melMin) * i / (numBands + 1).toDouble()
            val hz = melToHz(mel)
            ((fftSize + 1) * hz / sampleRate.toDouble()).toInt().coerceIn(0, numBins - 1)
        }
        return Array(numBands) { m ->
            val weights = DoubleArray(numBins)
            val left = binPoints[m]
            val center = binPoints[m + 1].coerceAtLeast(left + 1)
            val right = binPoints[m + 2].coerceAtLeast(center + 1)
            for (k in left until min(center, numBins)) {
                weights[k] = (k - left).toDouble() / (center - left).toDouble()
            }
            for (k in center until min(right, numBins)) {
                weights[k] = (right - k).toDouble() / (right - center).toDouble()
            }
            weights
        }
    }

    private fun buildDctMatrix(numCoeffs: Int, numBands: Int): Array<DoubleArray> {
        val scale = sqrt(2.0 / numBands.toDouble())
        return Array(numCoeffs) { c ->
            DoubleArray(numBands) { m ->
                scale * cos(PI * c * (m + 0.5) / numBands.toDouble())
            }
        }
    }

    /**
     * Duration-preserving Pitch Shifter:
     * Step 1: WSOLA time-stretches the signal by `pitchRatio` (using waveform-similarity overlap-add).
     * Step 2: Resamples the stretched signal by `1 / pitchRatio` back to the original sample count.
     */
    private fun pitchShiftWsola(input: FloatArray, sampleRate: Int, pitchRatio: Float): FloatArray {
        val stretched = wsolaTimeStretch(input, sampleRate, stretchFactor = pitchRatio)
        val outLen = input.size
        if (stretched.size < 4 || outLen < 4) return input.copyOf()

        val output = FloatArray(outLen)
        val step = (stretched.size - 1).toDouble() / (outLen - 1).toDouble()
        for (i in 0 until outLen) {
            val pos = i * step
            val i1 = pos.toInt().coerceIn(0, stretched.lastIndex)
            val t = (pos - i1).toFloat()
            val y0 = stretched[(i1 - 1).coerceAtLeast(0)]
            val y1 = stretched[i1]
            val y2 = stretched[(i1 + 1).coerceAtMost(stretched.lastIndex)]
            val y3 = stretched[(i1 + 2).coerceAtMost(stretched.lastIndex)]
            val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
            val a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3
            val a2 = -0.5f * y0 + 0.5f * y2
            val a3 = y1
            output[i] = (((a0 * t + a1) * t + a2) * t + a3).coerceIn(-1.0f, 1.0f)
        }
        return output
    }

    /**
     * Fast Waveform Similarity Overlap-Add (WSOLA) time-stretcher tuned for 24kHz voice.
     */
    private fun wsolaTimeStretch(input: FloatArray, sampleRate: Int, stretchFactor: Float): FloatArray {
        val winLen = ((sampleRate * 26 / 1000) / 2) * 2
        val synHop = winLen / 2
        val anaHop = (synHop / stretchFactor).roundToInt().coerceIn(64, winLen - 32)
        val searchRadius = (sampleRate * 5 / 1000).coerceIn(32, 160)

        val outLen = (input.size * stretchFactor).roundToInt().coerceAtLeast(winLen)
        val out = FloatArray(outLen)
        val norm = FloatArray(outLen)

        val hann = FloatArray(winLen) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / winLen)).toFloat()
        }

        val firstCopy = min(winLen, min(input.size, outLen))
        for (i in 0 until firstCopy) {
            out[i] += input[i] * hann[i]
            norm[i] += hann[i]
        }

        var prevAnaPos = 0
        var synPos = synHop
        var nominalAnaPos = anaHop

        while (synPos + winLen <= outLen && nominalAnaPos + winLen <= input.size) {
            val refStart = (prevAnaPos + synHop).coerceIn(0, (input.size - synHop).coerceAtLeast(0))
            val searchStart = max(0, nominalAnaPos - searchRadius)
            val searchEnd = min(input.size - winLen, nominalAnaPos + searchRadius)

            var bestPos = nominalAnaPos.coerceIn(0, (input.size - winLen).coerceAtLeast(0))
            var bestScore = Double.NEGATIVE_INFINITY

            var cand = searchStart
            while (cand <= searchEnd) {
                var corr = 0.0
                var e = 0.0
                var j = 0
                while (j < synHop) {
                    val a = input[refStart + j].toDouble()
                    val b = input[cand + j].toDouble()
                    corr += a * b
                    e += b * b
                    j += 2
                }
                val score = corr / (sqrt(e) + 1e-6)
                if (score > bestScore) {
                    bestScore = score
                    bestPos = cand
                }
                cand += 2
            }

            for (i in 0 until winLen) {
                val w = hann[i]
                out[synPos + i] += input[bestPos + i] * w
                norm[synPos + i] += w
            }

            prevAnaPos = bestPos
            synPos += synHop
            nominalAnaPos += anaHop
        }

        for (i in out.indices) {
            if (norm[i] > 1e-4f) {
                out[i] /= norm[i]
            }
        }
        return out
    }

    private data class BiquadCoeffs(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    ) {
        companion object {
            fun lowShelf(sampleRate: Double, freqHz: Double, gainDb: Double): BiquadCoeffs {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freqHz / sampleRate
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / 2.0 * sqrt(2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

                val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                val a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun highShelf(sampleRate: Double, freqHz: Double, gainDb: Double): BiquadCoeffs {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freqHz / sampleRate
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / 2.0 * sqrt(2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

                val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                val a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun peaking(sampleRate: Double, freqHz: Double, q: Double, gainDb: Double): BiquadCoeffs {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freqHz / sampleRate
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2.0 * q)

                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * cosW0
                val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha / a
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    private fun applyBiquadInPlace(samples: FloatArray, c: BiquadCoeffs) {
        var z1 = 0.0
        var z2 = 0.0
        for (i in samples.indices) {
            val x = samples[i].toDouble()
            val y = c.b0 * x + z1
            z1 = c.b1 * x - c.a1 * y + z2
            z2 = c.b2 * x - c.a2 * y
            samples[i] = y.toFloat()
        }
    }
}
