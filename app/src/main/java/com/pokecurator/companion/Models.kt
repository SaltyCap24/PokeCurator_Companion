package com.pokecurator.companion

import org.json.JSONObject

/** One specimen shown in a zone (keeper / transfer / review). */
data class Specimen(
    val cp: Int?,
    val iv: Double?,
    val badges: List<String>,
    val inspect: Boolean,
    val why: String,
) {
    fun line(): String {
        val ivStr = iv?.let { "${it.toInt()}%" } ?: "?"
        val b = if (badges.isNotEmpty()) "  " + badges.joinToString(" ") else ""
        val inspectFlag = if (inspect) "  \uD83D\uDD0E" else ""
        return "CP ${cp ?: "?"}   IV $ivStr$b$inspectFlag"
    }
}

/** One species step in the guided cleanup plan. */
data class Step(
    val species: String,
    val dex: Int?,
    val owned: Int,
    val keep: Int,
    val transferCount: Int,
    val tradeCount: Int,
    val reviewCount: Int,
    val promoteCount: Int,
    val searchSpecies: String,
    val searchTransfer: String,
    val searchAmbiguous: Boolean,
    val promote: List<Specimen>,
    val review: List<Specimen>,
    val trade: List<Specimen>,
    val transfer: List<Specimen>,
)

/** The whole plan returned by /api/export/transfer-session. */
data class Plan(
    val mode: String,
    val favoritePct: Int,
    val totalTransfer: Int,
    val steps: List<Step>,
) {
    companion object {
        fun parse(json: String): Plan {
            val o = JSONObject(json)
            val stepsArr = o.optJSONArray("steps")
            val steps = ArrayList<Step>()
            if (stepsArr != null) {
                for (i in 0 until stepsArr.length()) {
                    steps.add(parseStep(stepsArr.getJSONObject(i)))
                }
            }
            return Plan(
                mode = o.optString("mode", "auto"),
                favoritePct = o.optInt("favorite_pct", 0),
                totalTransfer = o.optInt("total_transfer", 0),
                steps = steps,
            )
        }

        private fun parseStep(o: JSONObject): Step = Step(
            species = o.optString("species"),
            dex = if (o.isNull("dex")) null else o.optInt("dex"),
            owned = o.optInt("owned"),
            keep = o.optInt("keep"),
            transferCount = o.optInt("transfer_count"),
            tradeCount = o.optInt("trade_count"),
            reviewCount = o.optInt("review_count"),
            promoteCount = o.optInt("promote_count"),
            searchSpecies = o.optString("search_species"),
            searchTransfer = o.optString("search_transfer"),
            searchAmbiguous = o.optBoolean("search_ambiguous", false),
            promote = specimens(o, "promote"),
            review = specimens(o, "review"),
            trade = specimens(o, "trade"),
            transfer = specimens(o, "transfer"),
        )

        private fun specimens(o: JSONObject, key: String): List<Specimen> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<Specimen>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val badges = ArrayList<String>()
                s.optJSONArray("badges")?.let { ba ->
                    for (j in 0 until ba.length()) badges.add(ba.getString(j))
                }
                out.add(
                    Specimen(
                        cp = if (s.isNull("cp")) null else s.optInt("cp"),
                        iv = if (s.isNull("iv")) null else s.optDouble("iv"),
                        badges = badges,
                        inspect = s.optBoolean("inspect", false),
                        why = s.optString("why", ""),
                    )
                )
            }
            return out
        }
    }
}
