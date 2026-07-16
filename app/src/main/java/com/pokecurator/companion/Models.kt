package com.pokecurator.companion

import org.json.JSONObject

/** One specimen shown in a zone (keeper / transfer / review). */
data class Specimen(
    val cp: Int?,
    val iv: Double?,
    val atk: Int?,
    val def: Int?,
    val sta: Int?,
    val level: Double?,
    val gender: String,
    val form: String,
    val badges: List<String>,
    val inspect: Boolean,
    val why: String,
) {
    fun line(): String {
        val ivStr = iv?.let { "${it.toInt()}%" } ?: "?"
        // Exact Atk/Def/Sta spread + gender + level: what the in-game appraisal
        // shows, so same-CP/same-% specimens can be told apart.
        val spread = if (atk != null && def != null && sta != null) " $atk/$def/$sta" else ""
        val g = when (gender) {
            "male" -> " \u2642"
            "female" -> " \u2640"
            else -> ""
        }
        val lv = level?.let { l ->
            " L" + (if (l % 1.0 == 0.0) l.toInt().toString() else l.toString())
        } ?: ""
        val b = if (badges.isNotEmpty()) "  " + badges.joinToString(" ") else ""
        val inspectFlag = if (inspect) "  \uD83D\uDD0E" else ""
        // Lead with the form/letter (Unown A, Flabebe White, Alolan, ...) so a
        // merged multi-form step is readable at a glance.
        val f = if (form.isNotEmpty()) "[$form] " else ""
        return "${f}CP ${cp ?: "?"}  $ivStr$spread$g$lv$b$inspectFlag"
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
    val searchPromote: String,
    val promoteAmbiguous: Boolean,
    val searchReview: String,
    val reviewAmbiguous: Boolean,
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
            searchPromote = o.optString("search_promote"),
            promoteAmbiguous = o.optBoolean("promote_ambiguous", false),
            searchReview = o.optString("search_review"),
            reviewAmbiguous = o.optBoolean("review_ambiguous", false),
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
                        atk = if (s.isNull("atk")) null else s.optInt("atk"),
                        def = if (s.isNull("def")) null else s.optInt("def"),
                        sta = if (s.isNull("sta")) null else s.optInt("sta"),
                        level = if (s.isNull("level")) null else s.optDouble("level"),
                        gender = s.optString("gender", ""),
                        form = s.optString("form", ""),
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
