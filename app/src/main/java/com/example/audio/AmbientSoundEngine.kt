package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 100% Offline gentle background ambient tracks synthesized in real time on-device,
 * plus support for custom user-imported looping audio files.
 */
enum class AmbientSoundTrack(
    val storageKey: String,
    val label: String,
    val subtitle: String
) {
    NONE(
        storageKey = "none",
        label = "Tắt",
        subtitle = "Chỉ phát giọng đọc"
    ),
    SOFT_RAIN(
        storageKey = "soft_rain",
        label = "Mưa rơi nhẹ",
        subtitle = "Tiếng mưa êm dịu thư giãn"
    ),
    MELODIC_PAD(
        storageKey = "melodic_pad",
        label = "Giai điệu du dương",
        subtitle = "Hòa âm nhẹ nhàng sâu lắng"
    ),
    NIGHT_STREAM(
        storageKey = "night_stream",
        label = "Suối đêm êm đềm",
        subtitle = "Tiếng suối chảy và gió thoảng"
    );

    companion object {
        fun fromKey(key: String?): AmbientSoundTrack {
            return entries.firstOrNull { it.storageKey == key } ?: NONE
        }
    }
}

/**
 * Real-time procedural 16-bit PCM ambient audio player using Android [AudioTrack],
 * plus looping [MediaPlayer] support for user-imported background music files.
 * Designed to sit softly beneath TTS narration without drowning out speech.
 */
class AmbientSoundEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null

    @Volatile
    private var activeTrack: AmbientSoundTrack = AmbientSoundTrack.NONE

    @Volatile
    private var activeCustomFilePath: String? = null

    @Volatile
    private var targetVolume: Float = 0.20f

    private val mediaPlayerLock = Any()
    private var customMediaPlayer: MediaPlayer? = null

    fun startCustomFile(filePath: String, volume: Float = 0.20f) {
        val clampedVol = volume.coerceIn(0.05f, 0.45f)
        targetVolume = clampedVol
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            stop()
            return
        }
        synchronized(mediaPlayerLock) {
            val currentPlayer = customMediaPlayer
            if (activeCustomFilePath == filePath && currentPlayer != null) {
                runCatching {
                    currentPlayer.setVolume(clampedVol, clampedVol)
                    if (!currentPlayer.isPlaying) currentPlayer.start()
                }
                return
            }
        }
        stop()
        activeCustomFilePath = filePath
        synchronized(mediaPlayerLock) {
            runCatching {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(filePath)
                    isLooping = true
                    prepare()
                    setVolume(clampedVol, clampedVol)
                    start()
                }
                customMediaPlayer = player
            }.onFailure {
                activeCustomFilePath = null
                stopCustomMediaPlayerLocked()
            }
        }
    }

    fun start(track: AmbientSoundTrack, volume: Float = 0.20f) {
        targetVolume = volume.coerceIn(0.05f, 0.45f)
        if (track == AmbientSoundTrack.NONE) {
            stop()
            return
        }
        if (activeTrack == track && activeCustomFilePath == null && playJob?.isActive == true) {
            return
        }
        stop()
        activeTrack = track

        playJob = scope.launch {
            val sampleRate = 22050
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val bufferSamples = (minBuf / 2).coerceAtLeast(2048)

            val audioTrack = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSamples * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@launch

            val pcmBuffer = ShortArray(bufferSamples)
            val rng = Random(System.nanoTime())

            // Filter & synth states
            var lp1 = 0.0
            var lp2 = 0.0
            var lp3 = 0.0
            var sampleCounter = 0L
            var fadeGain = 0.0

            // Pentatonic ambient chord progression (Hz): Cadd9 -> Am7 -> Fmaj7 -> G6
            val chords = arrayOf(
                doubleArrayOf(261.63, 329.63, 392.00, 587.33),
                doubleArrayOf(220.00, 261.63, 329.63, 493.88),
                doubleArrayOf(174.61, 261.63, 329.63, 440.00),
                doubleArrayOf(196.00, 293.66, 392.00, 493.88)
            )
            val melodyNotes = doubleArrayOf(523.25, 587.33, 659.25, 783.99, 659.25, 587.33, 523.25, 440.00)

            try {
                audioTrack.play()
                while (isActive && activeTrack == track) {
                    val vol = targetVolume.toDouble()
                    for (i in 0 until bufferSamples) {
                        val t = sampleCounter.toDouble() / sampleRate
                        // Smooth 1.2s fade-in to avoid clicks
                        if (fadeGain < 1.0) {
                            fadeGain = (fadeGain + 1.0 / (sampleRate * 1.2)).coerceAtMost(1.0)
                        }

                        val sampleVal = when (track) {
                            AmbientSoundTrack.SOFT_RAIN -> {
                                // Warm low-passed rain patter with gentle undulating breeze
                                val white = rng.nextDouble(-1.0, 1.0)
                                lp1 = lp1 * 0.86 + white * 0.14
                                lp2 = lp2 * 0.94 + lp1 * 0.06
                                val dropNoise = if (rng.nextInt(140) == 0) rng.nextDouble(-0.22, 0.22) else 0.0
                                lp3 = lp3 * 0.72 + dropNoise * 0.28
                                val swell = 0.78 + 0.22 * sin(2.0 * PI * 0.09 * t)
                                (lp2 * 2.1 + lp3 * 0.75) * swell
                            }

                            AmbientSoundTrack.MELODIC_PAD -> {
                                // Serene ambient pad + soft bell melody every 2.2s
                                val chordDuration = 6.4
                                val chordIdx = ((t / chordDuration).toInt()) % chords.size
                                val chordPhase = (t % chordDuration) / chordDuration
                                // Smooth crossfade envelope per chord
                                val chordEnv = sin(PI * chordPhase).coerceIn(0.0, 1.0)
                                val chord = chords[chordIdx]
                                var pad = 0.0
                                for (idx in chord.indices) {
                                    val freq = chord[idx]
                                    val detune = 1.0 + 0.0012 * sin(2.0 * PI * (0.18 + idx * 0.05) * t)
                                    pad += sin(2.0 * PI * freq * detune * t) * (0.24 - idx * 0.03)
                                }
                                // Gentle chime note every 2.4s
                                val noteLen = 2.4
                                val noteIdx = ((t / noteLen).toInt()) % melodyNotes.size
                                val noteT = t % noteLen
                                val noteEnv = if (noteT < 0.06) {
                                    noteT / 0.06
                                } else {
                                    exp(-noteT * 1.55)
                                }
                                val noteFreq = melodyNotes[noteIdx]
                                val chime = (sin(2.0 * PI * noteFreq * t) +
                                    0.18 * sin(2.0 * PI * noteFreq * 2.0 * t)) * noteEnv * 0.22

                                (pad * (0.55 + 0.45 * chordEnv) + chime) * 0.75
                            }

                            AmbientSoundTrack.NIGHT_STREAM -> {
                                // Smooth babbling water stream + low gentle night wind
                                val white = rng.nextDouble(-1.0, 1.0)
                                lp1 = lp1 * 0.965 + white * 0.035 // low breeze
                                lp2 = lp2 * 0.78 + white * 0.22   // water shimmer
                                val ripple = 0.55 + 0.45 * sin(2.0 * PI * 0.31 * t + sin(2.0 * PI * 0.13 * t))
                                val shimmer = (lp2 - lp1) * (0.52 + 0.28 * sin(2.0 * PI * 0.67 * t))
                                val lowWind = lp1 * 1.9
                                val warmHum = sin(2.0 * PI * 146.83 * t) * 0.055 * (0.6 + 0.4 * sin(2.0 * PI * 0.07 * t))
                                (lowWind + shimmer * ripple * 1.15 + warmHum) * 0.95
                            }

                            AmbientSoundTrack.NONE -> 0.0
                        }

                        val scaled = (sampleVal * vol * fadeGain * Short.MAX_VALUE)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        pcmBuffer[i] = scaled.toShort()
                        sampleCounter++
                    }

                    val written = runCatching {
                        audioTrack.write(pcmBuffer, 0, bufferSamples)
                    }.getOrDefault(-1)
                    if (written < 0) break
                }
            } finally {
                runCatching {
                    audioTrack.pause()
                    audioTrack.flush()
                    audioTrack.stop()
                    audioTrack.release()
                }
            }
        }
    }

    fun updateVolume(volume: Float) {
        val clamped = volume.coerceIn(0.05f, 0.45f)
        targetVolume = clamped
        synchronized(mediaPlayerLock) {
            runCatching { customMediaPlayer?.setVolume(clamped, clamped) }
        }
    }

    fun stop() {
        activeTrack = AmbientSoundTrack.NONE
        activeCustomFilePath = null
        playJob?.cancel()
        playJob = null
        synchronized(mediaPlayerLock) {
            stopCustomMediaPlayerLocked()
        }
    }

    private fun stopCustomMediaPlayerLocked() {
        val player = customMediaPlayer
        customMediaPlayer = null
        if (player != null) {
            runCatching {
                if (player.isPlaying) player.stop()
            }
            runCatching { player.release() }
        }
    }
}
