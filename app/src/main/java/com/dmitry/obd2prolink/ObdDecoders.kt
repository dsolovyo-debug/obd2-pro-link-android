package com.dmitry.obd2prolink

object ObdDecoders {

    fun cleanHex(raw: String): String =
        raw.uppercase().replace(Regex("[^0-9A-F]"), "")

    fun findPidBytes(raw: String, modeHex: String, pidHex: String, needed: Int): List<Int>? {
        val hex = cleanHex(raw)
        val needle = (modeHex + pidHex).uppercase()
        val idx = hex.indexOf(needle)
        if (idx < 0) return null
        val start = idx + needle.length
        if (start + needed * 2 > hex.length) return null
        return (0 until needed).map { i ->
            hex.substring(start + i * 2, start + i * 2 + 2).toInt(16)
        }
    }

    fun decodeRpm(raw: String): Double? =
        findPidBytes(raw, "41", "0C", 2)?.let { ((it[0] * 256) + it[1]) / 4.0 }

    fun decodeSpeedMph(raw: String): Double? =
        findPidBytes(raw, "41", "0D", 1)?.let { it[0] * 0.621371 }

    fun decodeCoolantF(raw: String): Double? =
        findPidBytes(raw, "41", "05", 1)?.let { ((it[0] - 40) * 9.0 / 5.0) + 32.0 }

    fun decodeIntakeF(raw: String): Double? =
        findPidBytes(raw, "41", "0F", 1)?.let { ((it[0] - 40) * 9.0 / 5.0) + 32.0 }

    fun decodeLoadPct(raw: String): Double? =
        findPidBytes(raw, "41", "04", 1)?.let { (it[0] * 100.0) / 255.0 }

    fun decodeThrottlePct(raw: String): Double? =
        findPidBytes(raw, "41", "11", 1)?.let { (it[0] * 100.0) / 255.0 }

    fun decodeMafGs(raw: String): Double? =
        findPidBytes(raw, "41", "10", 2)?.let { ((it[0] * 256) + it[1]) / 100.0 }

    fun decodeFuelTrimPct(raw: String, pidHex: String): Double? =
        findPidBytes(raw, "41", pidHex, 1)?.let { ((it[0] - 128) * 100.0) / 128.0 }

    fun decodeControlVoltage(raw: String): Double? =
        findPidBytes(raw, "41", "42", 2)?.let { ((it[0] * 256) + it[1]) / 1000.0 }

    fun decodeAtrv(raw: String): Double? {
        val m = Regex("(\\d+\\.\\d+)\\s*V", RegexOption.IGNORE_CASE).find(raw)
        return m?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun decodeVin(raw: String): String? {
        val hex = cleanHex(raw)
        val idx = hex.indexOf("490201")
        if (idx < 0) return null
        val payload = hex.substring(idx + 6)
        val sb = StringBuilder()
        var i = 0
        while (i + 2 <= payload.length && sb.length < 17) {
            val b = payload.substring(i, i + 2).toIntOrNull(16) ?: break
            if (b in 48..57 || b in 65..90) {
                sb.append(b.toChar())
            }
            i += 2
        }
        val vin = sb.toString()
        return if (vin.length >= 11) vin else null
    }

    fun decodeDtcs(raw: String, modeReplyHex: String): List<String> {
        val hex = cleanHex(raw)
        val idx = hex.indexOf(modeReplyHex.uppercase())
        if (idx < 0) return emptyList()
        val data = hex.substring(idx + modeReplyHex.length)
        val out = mutableListOf<String>()
        val prefixes = charArrayOf('P', 'C', 'B', 'U')
        var i = 0
        while (i + 4 <= data.length) {
            val chunk = data.substring(i, i + 4)
            i += 4
            if (chunk == "0000") continue
            val first = chunk.substring(0, 1).toIntOrNull(16) ?: continue
            val letter = prefixes[(first shr 2) and 0x03]
            val d1 = first and 0x03
            val code = "$letter$d1${chunk.substring(1)}"
            if (!out.contains(code)) out.add(code)
        }
        return out
    }
}
