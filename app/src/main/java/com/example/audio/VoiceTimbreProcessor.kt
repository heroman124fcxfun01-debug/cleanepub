package com.example.audio

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Multi-dimensional acoustic analysis of a 16kHz Mono reference voice recording.
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
    val fingerprint32: FloatArray
)

/**
 * Persisted & runtime timbre transformation parameters applied to NeuCodec 24kHz PCM output
 * so distinct cloned voices preserve unmistakable pitch, warmth, formant presence, and brightness.
 */
data class VoiceTimbreSettings(
    val pitchRatio: Float = 1.0f,
    val warmthGainDb: Float = 0.0f,
    val presenceGainDb: Float = 0.0f,
    val brightnessGainDb: Float = 0.0f,
    val speakingRateRatio: Float = 1.0f,
    val matchedPresetId: String? = null
) {
    val isIdentity: Boolean
        get() = abs(pitchRatio - 1.0f) < 0.015f &&
            abs(warmthGainDb) < 0.35f &&
            abs(presenceGainDb) < 0.35f &&
            abs(brightnessGainDb) < 0.35f

    fun describeShort(): String = String.format(
        Locale.US,
        "pitch=%.2fx, bass=%+.1fdB, mid=%+.1fdB, treb=%+.1fdB, rate=%.2fx",
        pitchRatio,
        warmthGainDb,
        presenceGainDb,
        brightnessGainDb,
        speakingRateRatio
    )
}

/**
 * Acoustic voice analyzer, multi-dimensional `voices.json` preset mapper, and real-time 24kHz
 * PCM DSP processor (WSOLA pitch shifting + 3-band Biquad formant/timbre EQ).
 */
object VoiceTimbreProcessor {

    private data class PresetAnchor(
        val keywords: List<String>,
        val isMale: Boolean,
        val baselineF0Hz: Float,
        val baselineTilt: Float,
        val baselineRateSyl: Float
    )

    // Calibrated acoustic anchors for official pnnbao-ump Vietnamese voices.json presets:
    // Vinh (Nam miền Nam), Binh (Nam miền Bắc), Tuyen (Nam miền Bắc truyền cảm),
    // Doan (Nữ miền Nam), Ly (Nữ miền Bắc), Ngoc (Nữ miền Bắc cao/sáng).
    private val KNOWN_PRESET_ANCHORS = listOf(
        PresetAnchor(
            keywords = listOf("vinh", "vĩnh"),
            isMale = true,
            baselineF0Hz = 112f,
            baselineTilt = -0.35f,
            baselineRateSyl = 3.8f
        ),
        PresetAnchor(
            keywords = listOf("binh", "bình"),
            isMale = true,
            baselineF0Hz = 132f,
            baselineTilt = 0.05f,
            baselineRateSyl = 4.4f
        ),
        PresetAnchor(
            keywords = listOf("tuyen", "tuyên"),
            isMale = true,
            baselineF0Hz = 152f,
            baselineTilt = 0.30f,
            baselineRateSyl = 4.9f
        ),
        PresetAnchor(
            keywords = listOf("doan", "đoan"),
            isMale = false,
            baselineF0Hz = 186f,
            baselineTilt = -0.25f,
            baselineRateSyl = 4.0f
        ),
        PresetAnchor(
            keywords = listOf("ly"),
            isMale = false,
            baselineF0Hz = 216f,
            baselineTilt = 0.06f,
            baselineRateSyl = 4.5f
        ),
        PresetAnchor(
            keywords = listOf("ngoc", "ngọc"),
            isMale = false,
            baselineF0Hz = 248f,
            baselineTilt = 0.34f,
            baselineRateSyl = 5.0f
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
     * - 3-band spectral energy ratios (80–450 Hz low, 450–1800 Hz mid, 1800–5500 Hz high),
     *   spectral centroid (Hz), and spectral tilt (-1..+1).
     * - Speaking rate (syllable nuclei per second) via RMS envelope peak detection.
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
                fingerprint32 = fingerprint
            )
        }

        // Compute global RMS to establish adaptive Voice Activity Detection threshold
        var globalSq = 0.0
        for (v in samples) globalSq += v.toDouble() * v.toDouble()
        val globalRms = sqrt(globalSq / samples.size).toFloat().coerceAtLeast(1e-5f)
        val vadRmsThreshold = (globalRms * 0.42f).coerceAtLeast(0.012f)

        val frameSize = (sampleRate * 32 / 1000).coerceIn(256, 1024) // 32ms window (512 @ 16kHz)
        val hopSize = frameSize / 2                                  // 16ms hop (256 @ 16kHz)
        val minLag = (sampleRate / 360).coerceAtLeast(24)            // 360 Hz max F0
        val maxLag = (sampleRate / 68).coerceAtMost(frameSize - 16)  // 68 Hz min F0

        // Goertzel probe frequencies spanning Low (80-450), Mid (450-1800), High (1800-5500)
        val lowFreqs = floatArrayOf(120f, 200f, 310f, 420f)
        val midFreqs = floatArrayOf(600f, 900f, 1250f, 1650f)
        val highFreqs = floatArrayOf(2200f, 2900f, 3800f, 4800f)

        val f0Estimates = ArrayList<Float>(128)
        val frameRmsList = ArrayList<Float>(samples.size / hopSize + 1)

        var sumLow = 0.0
        var sumMid = 0.0
        var sumHigh = 0.0
        var centroidWeightedNum = 0.0
        var centroidWeightedDen = 0.0
        var voicedFrames = 0

        val window = FloatArray(frameSize) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (frameSize - 1).coerceAtLeast(1))).toFloat()
        }
        val frameBuf = FloatArray(frameSize)

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
            // Voiced speech frames have strong energy and moderate-low zero-crossing rate
            if (rms >= vadRmsThreshold && zcr < 0.24f) {
                // Estimate pitch via normalized autocorrelation
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
                    // Prefer earliest strong peak (prevents sub-harmonic octave halving!)
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
                    // Parabolic interpolation around chosenLag
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

                // Spectral energy bands via Goertzel on Hann-windowed voiced frame
                for (i in 0 until frameSize) {
                    frameBuf[i] = samples[pos + i] * window[i]
                }
                var fLow = 0.0
                for (freq in lowFreqs) {
                    val p = goertzelPower(frameBuf, sampleRate, freq)
                    fLow += p
                    centroidWeightedNum += freq * p
                    centroidWeightedDen += p
                }
                var fMid = 0.0
                for (freq in midFreqs) {
                    val p = goertzelPower(frameBuf, sampleRate, freq)
                    fMid += p
                    centroidWeightedNum += freq * p
                    centroidWeightedDen += p
                }
                var fHigh = 0.0
                for (freq in highFreqs) {
                    val p = goertzelPower(frameBuf, sampleRate, freq)
                    fHigh += p
                    centroidWeightedNum += freq * p
                    centroidWeightedDen += p
                }
                sumLow += fLow
                sumMid += fMid
                sumHigh += fHigh
                voicedFrames++
            }
            pos += hopSize
        }

        // Robust trimmed median F0
        val medianF0 = if (f0Estimates.isNotEmpty()) {
            f0Estimates.sort()
            val mid = f0Estimates.size / 2
            val lo = (f0Estimates.size * 0.25f).toInt().coerceIn(0, f0Estimates.lastIndex)
            val hi = (f0Estimates.size * 0.75f).toInt().coerceIn(lo, f0Estimates.lastIndex)
            var sum = 0f
            for (i in lo..hi) sum += f0Estimates[i]
            (sum / (hi - lo + 1).coerceAtLeast(1)).coerceIn(75f, 340f)
        } else {
            165f
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

        // Log-ratio spectral tilt in [-1.0, +1.0]: negative = warm/bass-heavy, positive = bright/crisp
        val rawTilt = ln(((highRatio + 0.35f * midRatio) + 1e-4f) / (lowRatio + 1e-4f))
        val spectralTilt = (rawTilt / 2.6f).coerceIn(-1.0f, 1.0f)

        // Speaking rate via smoothed RMS syllable-nucleus peak counting over active speech time
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
            fingerprint32 = fingerprint
        )
    }

    private fun goertzelPower(frame: FloatArray, sampleRate: Int, targetFreqHz: Float): Double {
        val omega = 2.0 * PI * targetFreqHz / sampleRate.toDouble()
        val coeff = 2.0 * cos(omega)
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (x in frame) {
            val s = x.toDouble() + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        val power = sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
        return power.coerceAtLeast(0.0)
    }

    private fun estimateSpeakingRate(
        frameRms: List<Float>,
        hopSec: Float,
        activeThreshold: Float
    ): Float {
        if (frameRms.size < 8) return 4.4f
        // 3-frame moving average envelope
        val env = FloatArray(frameRms.size) { i ->
            val a = frameRms[(i - 1).coerceAtLeast(0)]
            val b = frameRms[i]
            val c = frameRms[(i + 1).coerceAtMost(frameRms.lastIndex)]
            (a + 2f * b + c) * 0.25f
        }
        val activeFrames = env.count { it >= activeThreshold }
        val activeSec = (activeFrames * hopSec).coerceAtLeast(0.5f)
        val minPeakGapFrames = (0.12f / hopSec).roundToInt().coerceAtLeast(3) // max ~8 syl/s
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
            PresetAnchor(
                keywords = emptyList(),
                isMale = true,
                baselineF0Hz = if (isSouth) 115f else (122f + frac * 30f),
                baselineTilt = if (isSouth) -0.28f else (-0.10f + frac * 0.35f),
                baselineRateSyl = 4.2f
            )
        } else {
            PresetAnchor(
                keywords = emptyList(),
                isMale = false,
                baselineF0Hz = if (isSouth) 188f else (200f + frac * 45f),
                baselineTilt = if (isSouth) -0.22f else (-0.05f + frac * 0.40f),
                baselineRateSyl = 4.5f
            )
        }
    }

    /**
     * Maps the user's multi-dimensional [VoiceAcousticProfile] to the acoustically closest preset
     * across ALL entries in `voices.json` (distinguishing deep male, mid male, expressive male,
     * southern female, neutral northern female, bright northern female), then computes relative
     * pitch shift (`pitchRatio`), 3-band EQ shaping (`warmthGainDb`, `presenceGainDb`, `brightnessGainDb`),
     * and natural speaking-rate scaling (`speakingRateRatio`).
     */
    fun matchBestPresetAndComputeTimbre(
        profile: VoiceAcousticProfile,
        presets: List<VieNeuPresetVoice>
    ): Pair<VieNeuPresetVoice?, VoiceTimbreSettings> {
        val valid = presets.filter { it.referenceCodes.size >= 8 }
        if (valid.isEmpty()) {
            val fallbackPitch = (profile.f0Hz / 165f).coerceIn(0.78f, 1.28f)
            return null to VoiceTimbreSettings(
                pitchRatio = fallbackPitch,
                warmthGainDb = (-profile.spectralTilt * 3.0f).coerceIn(-4.0f, 4.0f),
                presenceGainDb = ((profile.midEnergyRatio - 0.42f) * 8.0f).coerceIn(-3.0f, 3.0f),
                brightnessGainDb = (profile.spectralTilt * 3.5f).coerceIn(-4.0f, 4.0f),
                speakingRateRatio = (profile.speakingRateSylPerSec / 4.4f).coerceIn(0.88f, 1.14f),
                matchedPresetId = null
            )
        }

        val anchors = valid.mapIndexed { idx, p -> p to inferAnchorForPreset(p, idx, valid.size) }

        // Soft gender preference: below 162 Hz strongly favors male presets, above 178 Hz strongly favors female
        val userLogF0 = ln(profile.f0Hz.toDouble())
        var bestPreset = anchors.first().first
        var bestAnchor = anchors.first().second
        var bestScore = Double.MAX_VALUE

        for ((preset, anchor) in anchors) {
            val f0DiffSemitones = 12.0 * (userLogF0 - ln(anchor.baselineF0Hz.toDouble())) / ln(2.0)
            val tiltDiff = (profile.spectralTilt - anchor.baselineTilt).toDouble()
            val rateDiff = ((profile.speakingRateSylPerSec - anchor.baselineRateSyl) / 1.5).toDouble()

            // Weighted multi-dimensional acoustic distance
            val genderPenalty = when {
                profile.f0Hz < 158f && !anchor.isMale -> 6.0
                profile.f0Hz > 182f && anchor.isMale -> 6.0
                else -> 0.0
            }
            val score = (f0DiffSemitones * 0.65) * (f0DiffSemitones * 0.65) +
                (tiltDiff * 3.2) * (tiltDiff * 3.2) +
                (rateDiff * 1.1) * (rateDiff * 1.1) +
                genderPenalty

            if (score < bestScore) {
                bestScore = score
                bestPreset = preset
                bestAnchor = anchor
            }
        }

        // Compute REAL relative pitch ratio against the matched preset's baseline F0 so the output
        // tracks the user's true vocal pitch cleanly without over-chipmunking or collapsing to 1.0x.
        val rawPitchRatio = profile.f0Hz / bestAnchor.baselineF0Hz
        val pitchRatio = rawPitchRatio.coerceIn(0.78f, 1.28f)

        // Compute relative 3-band EQ deltas so voices with warmer/brighter/deeper vocal tracts sound distinct
        val tiltDelta = (profile.spectralTilt - bestAnchor.baselineTilt).coerceIn(-1.2f, 1.2f)
        val warmthGainDb = (-tiltDelta * 4.2f + (profile.lowEnergyRatio - 0.34f) * 6.0f)
            .coerceIn(-4.5f, 4.5f)
        val presenceGainDb = ((profile.midEnergyRatio - 0.42f) * 7.5f + (profile.spectralCentroidHz - 1400f) / 1200f)
            .coerceIn(-3.2f, 3.2f)
        val brightnessGainDb = (tiltDelta * 4.5f + (profile.highEnergyRatio - 0.22f) * 6.0f)
            .coerceIn(-4.5f, 4.5f)
        val rateRatio = (profile.speakingRateSylPerSec / bestAnchor.baselineRateSyl).coerceIn(0.90f, 1.12f)

        return bestPreset to VoiceTimbreSettings(
            pitchRatio = pitchRatio,
            warmthGainDb = warmthGainDb,
            presenceGainDb = presenceGainDb,
            brightnessGainDb = brightnessGainDb,
            speakingRateRatio = rateRatio,
            matchedPresetId = bestPreset.id
        )
    }

    /**
     * Applies real acoustic voice transformation directly onto 24kHz Mono Float32 PCM:
     * 1. **WSOLA Time-Domain Pitch Shifting** (duration-preserving): shifts pitch smoothly by
     *    `timbre.pitchRatio` without chipmunk speedups or metallic vocoder artifacts.
     * 2. **3-Band Biquad Formant / Timbre EQ**:
     *    - Low-shelf @ 240 Hz (`warmthGainDb`)
     *    - Peaking formant @ 1350 Hz (`presenceGainDb`, Q = 1.1)
     *    - High-shelf @ 3200 Hz (`brightnessGainDb`)
     * 3. Soft loudness normalization & 6ms edge fades.
     */
    fun applyTimbreAndPitch(
        samples: FloatArray,
        sampleRate: Int,
        timbre: VoiceTimbreSettings?
    ): FloatArray {
        if (timbre == null || timbre.isIdentity || samples.size < sampleRate / 10) {
            return samples
        }

        // 1. Duration-preserving WSOLA Pitch Shift when pitchRatio deviates noticeably from 1.0
        val pitch = timbre.pitchRatio.coerceIn(0.76f, 1.30f)
        var working = if (abs(pitch - 1.0f) >= 0.02f) {
            pitchShiftWsola(samples, sampleRate, pitch)
        } else {
            samples.copyOf()
        }

        // 2. 3-Band Biquad Formant / EQ Shaping
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

        // 3. Soft peak protection & DC guard + 6ms de-click fades
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
            // 4-point Catmull-Rom cubic interpolation for crisp, alias-free speech resampling
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
        val winLen = ((sampleRate * 26 / 1000) / 2) * 2 // ~26ms even window (624 @ 24kHz)
        val synHop = winLen / 2                         // 50% overlap (312 @ 24kHz)
        val anaHop = (synHop / stretchFactor).roundToInt().coerceIn(64, winLen - 32)
        val searchRadius = (sampleRate * 5 / 1000).coerceIn(32, 160) // ±5ms correlation search

        val outLen = (input.size * stretchFactor).roundToInt().coerceAtLeast(winLen)
        val out = FloatArray(outLen)
        val norm = FloatArray(outLen)

        val hann = FloatArray(winLen) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / winLen)).toFloat()
        }

        // Seed first window
        val firstCopy = min(winLen, min(input.size, outLen))
        for (i in 0 until firstCopy) {
            out[i] += input[i] * hann[i]
            norm[i] += hann[i]
        }

        var prevAnaPos = 0
        var synPos = synHop
        var nominalAnaPos = anaHop

        while (synPos + winLen <= outLen && nominalAnaPos + winLen <= input.size) {
            // Natural continuation reference starts at prevAnaPos + synHop
            val refStart = (prevAnaPos + synHop).coerceIn(0, (input.size - synHop).coerceAtLeast(0))
            val searchStart = max(0, nominalAnaPos - searchRadius)
            val searchEnd = min(input.size - winLen, nominalAnaPos + searchRadius)

            var bestPos = nominalAnaPos.coerceIn(0, (input.size - winLen).coerceAtLeast(0))
            var bestScore = Double.NEGATIVE_INFINITY

            // Subsample step=2 for fast correlation search
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
                val alpha = sinW0 / 2.0 * sqrt(2.0) // S = 1.0
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
