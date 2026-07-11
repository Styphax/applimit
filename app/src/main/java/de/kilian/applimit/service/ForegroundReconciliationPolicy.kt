package de.kilian.applimit.service

/** Pure decision policy for correcting stale foreground state from accessibility windows. */
object ForegroundReconciliationPolicy {
    enum class CandidateAction {
        TRACK,
        CLEAR,
        IGNORE,
    }

    data class Candidate(
        val packageName: String?,
        val className: String?,
        val action: CandidateAction,
        val reason: String,
    )

    enum class Action {
        KEEP,
        TRACK,
        CLEAR,
    }

    data class Resolution(
        val action: Action,
        val packageName: String?,
        val observedPackage: String?,
        val reason: String,
    )

    /**
     * Candidates are ordered from the authoritative top window downwards. Ignored overlays do
     * not prove that the tracked app left the foreground, so the first decisive window below
     * them wins. With no decisive window, the current state is retained.
     */
    fun resolve(
        currentPackage: String?,
        candidates: List<Candidate>,
    ): Resolution {
        val decisive = candidates.firstOrNull { it.action != CandidateAction.IGNORE }
            ?: return Resolution(Action.KEEP, currentPackage, null, "NO_DECISIVE_WINDOW")

        return when (decisive.action) {
            CandidateAction.TRACK -> {
                val observed = decisive.packageName
                if (observed == null || observed == currentPackage) {
                    Resolution(Action.KEEP, currentPackage, observed, decisive.reason)
                } else {
                    Resolution(Action.TRACK, observed, observed, decisive.reason)
                }
            }

            CandidateAction.CLEAR -> if (currentPackage == null) {
                Resolution(Action.KEEP, null, decisive.packageName, decisive.reason)
            } else {
                Resolution(Action.CLEAR, null, decisive.packageName, decisive.reason)
            }

            CandidateAction.IGNORE -> error("Ignored candidates cannot be decisive")
        }
    }
}
