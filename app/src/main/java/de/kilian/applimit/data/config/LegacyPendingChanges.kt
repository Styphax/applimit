package de.kilian.applimit.data.config

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Read-only compatibility model for M5 rows. No v5 write path creates these values. */
enum class LegacyPendingChangeKind {
    REMOVE_APP,
    DELETE_GROUP,
    ASSIGN_APP_GROUP,
    REPLACE_PLANS,
    UPSERT_LIMIT_RULE,
    DELETE_LIMIT_RULE,
}

sealed interface LegacyPendingOperation {
    data class RemoveApp(val packageName: String) : LegacyPendingOperation
    data class DeleteGroup(val groupId: Long) : LegacyPendingOperation
    data class AssignAppGroup(val packageName: String, val groupId: Long?) : LegacyPendingOperation
    data class ReplacePlans(val plans: List<PlanInput>) : LegacyPendingOperation
    data class UpsertLimitRule(val rule: LimitRuleInput) : LegacyPendingOperation
    data class DeleteLimitRule(
        val planId: Long,
        val target: LimitTarget,
        val type: LimitRuleType,
    ) : LegacyPendingOperation
}

data class LegacyPendingChange(
    val id: String,
    val kind: LegacyPendingChangeKind,
    val operation: LegacyPendingOperation,
    val createdAtEpochMillis: Long,
)

/** Decoder retained solely to consume rows written by the removed M5 queue. */
object LegacyPendingOperationCodec {
    fun decode(kind: LegacyPendingChangeKind, payload: String): LegacyPendingOperation {
        val values = parseFields(payload)
        return when (kind) {
            LegacyPendingChangeKind.REMOVE_APP -> LegacyPendingOperation.RemoveApp(values.single())
            LegacyPendingChangeKind.DELETE_GROUP ->
                LegacyPendingOperation.DeleteGroup(values.single().toLong())
            LegacyPendingChangeKind.ASSIGN_APP_GROUP -> LegacyPendingOperation.AssignAppGroup(
                packageName = values[0],
                groupId = values[1].takeIf(String::isNotEmpty)?.toLong(),
            )
            LegacyPendingChangeKind.REPLACE_PLANS -> LegacyPendingOperation.ReplacePlans(
                values.map { encodedPlan ->
                    val plan = parseFields(encodedPlan)
                    PlanInput(
                        id = plan[0].takeIf(String::isNotEmpty)?.toLong(),
                        name = plan[1],
                        weekdays = java.time.DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
                            plan[2].toInt() and (1 shl (day.value - 1)) != 0
                        },
                    )
                },
            )
            LegacyPendingChangeKind.UPSERT_LIMIT_RULE ->
                LegacyPendingOperation.UpsertLimitRule(decodeRule(values))
            LegacyPendingChangeKind.DELETE_LIMIT_RULE -> LegacyPendingOperation.DeleteLimitRule(
                planId = values[0].toLong(),
                target = target(values[1], values[2]),
                type = LimitRuleType.valueOf(values[3]),
            )
        }
    }

    private fun decodeRule(values: List<String>) = LimitRuleInput(
        id = values[0].takeIf(String::isNotEmpty)?.toLong(),
        planId = values[1].toLong(),
        target = target(values[2], values[3]),
        type = LimitRuleType.valueOf(values[4]),
        value = values[5].toInt(),
        endMinute = values[6].takeIf(String::isNotEmpty)?.toInt(),
    )

    private fun target(type: String, id: String): LimitTarget = when (type) {
        "APP" -> LimitTarget.App(id)
        "GROUP" -> LimitTarget.Group(id.toLong())
        else -> error("Unknown target type: $type")
    }

    private fun parseFields(encoded: String): List<String> = if (encoded.isEmpty()) {
        listOf("")
    } else {
        encoded.split('.').map { value ->
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }
    }
}
