package com.dmitry.obd2prolink

import org.json.JSONObject

object ObdDecoders {

    fun cleanHex(raw: String): String =
        raw.uppercase().replace(Regex("[^0-9A-F]"), "")

    fun reassembleIsoTpHex(raw: String): String {
        val up = raw.uppercase()
        if (Regex("\\b0:").containsMatchIn(up)) {
            val parts = Regex("[0-9A-F]:([0-9A-F]+)").findAll(up).map { it.groupValues[1] }.toList()
            if (parts.isNotEmpty()) return parts.joinToString("")
        }
        val tokens = up.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isNotEmpty() && tokens.all { Regex("^7[0-9A-F]{2}[12][0-9A-F]").containsMatchIn(it) }) {
            return tokens.joinToString("") { t -> if (t[3] == '1') t.substring(7) else t.substring(5) }
        }
        return cleanHex(up)
    }

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


    fun decodeMapKpa(raw: String): Double? =
        findPidBytes(raw, "41", "0B", 1)?.let { it[0].toDouble() }

    fun decodeTimingDeg(raw: String): Double? =
        findPidBytes(raw, "41", "0E", 1)?.let { (it[0] / 2.0) - 64.0 }

    fun decodeFuelRailPsi(raw: String): Double? =
        findPidBytes(raw, "41", "59", 2)?.let { ((it[0] * 256) + it[1]) * 10.0 * 0.145038 }

    fun decodeO2LambdaAndMa(raw: String): Pair<Double, Double>? =
        findPidBytes(raw, "41", "34", 4)?.let {
            val lam = ((it[0] * 256) + it[1]) * 2.0 / 65536.0
            val ma = (((it[2] * 256) + it[3]) / 256.0) - 128.0
            Pair(lam, ma)
        }

    fun decodeCatTempF(raw: String, pidHex: String): Double? =
        findPidBytes(raw, "41", pidHex, 2)?.let { ((((it[0] * 256) + it[1]) / 10.0) - 40.0) * 9.0 / 5.0 + 32.0 }

    fun decodePct(raw: String, pidHex: String): Double? =
        findPidBytes(raw, "41", pidHex, 1)?.let { (it[0] * 100.0) / 255.0 }

    fun decodeAmbientF(raw: String): Double? =
        findPidBytes(raw, "41", "46", 1)?.let { ((it[0] - 40) * 9.0 / 5.0) + 32.0 }

    fun decodeBaroKpa(raw: String): Double? =
        findPidBytes(raw, "41", "33", 1)?.let { it[0].toDouble() }

    fun decodeEvapVaporKpa(raw: String): Double? =
        findPidBytes(raw, "41", "53", 2)?.let { ((it[0] * 256) + it[1]) / 200.0 }

    fun decodeActualTorquePct(raw: String): Double? =
        findPidBytes(raw, "41", "62", 1)?.let { (it[0] - 125).toDouble() }

    fun decodeFuelRateGs(raw: String): Double? =
        findPidBytes(raw, "41", "9D", 2)?.let { ((it[0] * 256) + it[1]) * 0.02 }

    fun decodeAtrv(raw: String): Double? {
        val m = Regex("(\\d+\\.\\d+)\\s*V", RegexOption.IGNORE_CASE).find(raw)
        return m?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun decodeVin(raw: String): String? {
        val hex = reassembleIsoTpHex(raw)
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
        if (raw.isBlank() || raw.contains("NO DATA", ignoreCase = true)) return emptyList()
        val prefix = modeReplyHex.uppercase()
        val out = mutableListOf<String>()
        val prefixes = charArrayOf('P', 'C', 'B', 'U')
        val frames = raw.uppercase().split(Regex("[\\r\\n\\s]+")).filter { it.isNotEmpty() }
        for (frame in frames) {
            val clean = cleanHex(frame)
            val idx = clean.indexOf(prefix)
            if (idx < 0) continue
            val payload = clean.substring(idx + prefix.length)
            if (payload.length < 2) continue
            val count = payload.substring(0, 2).toIntOrNull(16) ?: 0
            if (count == 0) continue
            val data = payload.substring(2)
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
        }
        return out
    }

    fun decodeHyundaiHvBms(raw: String): JSONObject? {
        val hex = reassembleIsoTpHex(raw)
        val idx = hex.indexOf("620101")
        if (idx < 0) return null
        val p = hex.substring(idx + 6)
        if (p.length < 54) return null
        val b = IntArray(p.length / 2) { i -> p.substring(i * 2, i * 2 + 2).toInt(16) }
        if (b.size < 27) return null
        val socPct = b[4] / 2.0
        var rawCurr = (b[10] shl 8) or b[11]
        if (rawCurr >= 32768) rawCurr -= 65536
        val currentA = rawCurr / 10.0
        val packV = ((b[12] shl 8) or b[13]) / 10.0
        val tMaxC = if (b[14] < 128) b[14] else b[14] - 256
        val tMinC = if (b[15] < 128) b[15] else b[15] - 256
        val cellMaxV = b[23] * 0.02
        val cellMinV = b[25] * 0.02
        return JSONObject().apply {
            put("socPct", Math.round(socPct * 10.0) / 10.0)
            put("packV", Math.round(packV * 10.0) / 10.0)
            put("currentA", Math.round(currentA * 10.0) / 10.0)
            put("cellMaxV", Math.round(cellMaxV * 100.0) / 100.0)
            put("cellMinV", Math.round(cellMinV * 100.0) / 100.0)
            put("cellDeltaMv", Math.round((cellMaxV - cellMinV) * 1000.0))
            put("tempMaxC", tMaxC)
            put("tempMinC", tMinC)
        }
    }
}
