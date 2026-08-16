package dev.kaiharimoto.masterkey.core

import java.io.ByteArrayOutputStream

/**
 * Builds Standard MIDI Files byte by byte so the loader can be tested against
 * exactly the quirks that appear in real files — running status, note-off as
 * velocity 0, mid-piece tempo changes, SMPTE division — without needing binary
 * fixtures checked into the repo.
 */
class TestSmf(
    private val format: Int = 1,
    private val division: Int = 480,
) {
    private val tracks = mutableListOf<ByteArray>()

    class Track {
        private val out = ByteArrayOutputStream()

        fun raw(deltaTicks: Int, vararg bytes: Int): Track {
            writeVarLen(out, deltaTicks)
            bytes.forEach { out.write(it and 0xFF) }
            return this
        }

        fun noteOn(deltaTicks: Int, channel: Int, pitch: Int, velocity: Int = 100): Track =
            raw(deltaTicks, 0x90 or channel, pitch, velocity)

        fun noteOff(deltaTicks: Int, channel: Int, pitch: Int): Track =
            raw(deltaTicks, 0x80 or channel, pitch, 0)

        /** The note-off form most real files actually emit. */
        fun noteOnZeroVelocity(deltaTicks: Int, channel: Int, pitch: Int): Track =
            raw(deltaTicks, 0x90 or channel, pitch, 0)

        /** Omits the status byte, exercising running status. */
        fun runningStatus(deltaTicks: Int, data1: Int, data2: Int): Track =
            raw(deltaTicks, data1, data2)

        fun tempo(deltaTicks: Int, microsPerQuarter: Int): Track = raw(
            deltaTicks, 0xFF, 0x51, 0x03,
            (microsPerQuarter shr 16) and 0xFF,
            (microsPerQuarter shr 8) and 0xFF,
            microsPerQuarter and 0xFF,
        )

        fun timeSignature(deltaTicks: Int, numerator: Int, denominatorPowerOfTwo: Int): Track =
            raw(deltaTicks, 0xFF, 0x58, 0x04, numerator, denominatorPowerOfTwo, 24, 8)

        fun keySignature(deltaTicks: Int, sharps: Int, minor: Boolean): Track =
            raw(deltaTicks, 0xFF, 0x59, 0x02, sharps and 0xFF, if (minor) 1 else 0)

        fun endOfTrack(deltaTicks: Int = 0): Track = raw(deltaTicks, 0xFF, 0x2F, 0x00)

        fun build(): ByteArray = out.toByteArray()
    }

    fun track(block: Track.() -> Unit): TestSmf {
        val t = Track()
        t.block()
        t.endOfTrack()
        tracks += t.build()
        return this
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        writeInt32(out, 6)
        writeInt16(out, format)
        writeInt16(out, tracks.size)
        writeInt16(out, division)
        for (track in tracks) {
            out.write("MTrk".toByteArray())
            writeInt32(out, track.size)
            out.write(track)
        }
        return out.toByteArray()
    }

    companion object {
        /** SMPTE division: negative frame rate in the high byte, ticks/frame in the low. */
        fun smpteDivision(frameRate: Int, ticksPerFrame: Int): Int =
            ((-frameRate and 0xFF) shl 8) or (ticksPerFrame and 0xFF)

        private fun writeInt16(out: ByteArrayOutputStream, value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
            out.write((value shr 24) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        private fun writeVarLen(out: ByteArrayOutputStream, value: Int) {
            var buffer = value.toLong() and 0x7F
            var v = value ushr 7
            while (v > 0) {
                buffer = (buffer shl 8) or 0x80 or (v and 0x7F).toLong()
                v = v ushr 7
            }
            while (true) {
                out.write((buffer and 0xFF).toInt())
                if (buffer and 0x80L != 0L) buffer = buffer shr 8 else break
            }
        }
    }
}
