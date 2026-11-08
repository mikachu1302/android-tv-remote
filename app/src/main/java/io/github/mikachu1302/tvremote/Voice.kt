package io.github.mikachu1302.tvremote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException

private const val SAMPLE_RATE = 8_000
private const val CHUNK_BYTES = 8 * 1024 // the TV's minimum chunk size

/**
 * Streams the phone's microphone to the TV's voice search for as long as it
 * runs. The microphone is only open between [start] and [stop] (the user holds
 * the mic button), and the audio goes only to the TV, nowhere else.
 */
class VoiceStreamer(private val conn: RemoteConnection, private val session: Int) {
    @Volatile private var running = true

    fun start() {
        Thread(::run, "tv-remote-voice").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission") // checked by the UI before starting
    private fun run() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK_BYTES * 2),
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return
            recorder.startRecording()
            val chunk = ByteArray(CHUNK_BYTES)
            var filled = 0
            while (running) {
                val n = recorder.read(chunk, filled, CHUNK_BYTES - filled)
                if (n <= 0) break
                filled += n
                if (filled == CHUNK_BYTES) {
                    conn.voiceChunk(session, chunk)
                    filled = 0
                }
            }
            if (filled > 0) { // pad the last piece up to the minimum size with silence
                chunk.fill(0, filled, CHUNK_BYTES)
                conn.voiceChunk(session, chunk)
            }
        } catch (_: IOException) {
            // The connection closed; the session is over.
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            runCatching { conn.voiceEnd(session) }
        }
    }
}
