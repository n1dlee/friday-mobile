package com.friday.ai.agent

import com.friday.ai.data.remote.groqJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One stored action: which tool, with which arguments, in which version of
 * the tools' language.
 *
 * Everything Friday keeps to run later — learned phrases, modes, NFC tags,
 * schedules — is an envelope, the same shape as a model's tool call. One
 * language means one interpreter ([AgentTools.interpret]) and one set of
 * checks, instead of a second command system for automation.
 */
data class ActionEnvelope(val tool: String, val args: JsonObject, val version: Int = ActionSchema.CURRENT) {

    fun toJson(): String = buildJsonObject {
        put("version", version)
        put("tool", tool)
        put("args", args)
    }.toString()
}

/**
 * Reads envelopes written by any version of Friday and brings them up to
 * the current tool language.
 *
 * v1: 0.9–0.11; no version field. `phone_control` named implementations
 * (`{"action":"wifi_panel"}`).
 * v2: `phone_control` names intents (`{"target":"wifi","state":"off"}`).
 *
 * An envelope that can't be brought up to date is dropped (null), never run
 * under its old meaning.
 */
object ActionSchema {

    const val CURRENT = 2

    fun parse(json: String): ActionEnvelope? = runCatching {
        val o = groqJson.parseToJsonElement(json).jsonObject
        val version = (o["version"] as? JsonPrimitive)?.intOrNull ?: 1
        ActionEnvelope(o.getValue("tool").jsonPrimitive.content, o.getValue("args").jsonObject, version)
    }.getOrNull()?.let(::migrate)

    fun migrate(envelope: ActionEnvelope): ActionEnvelope? {
        var e = envelope
        if (e.version > CURRENT) return null
        if (e.version == 1) e = v1toV2(e) ?: return null
        return e
    }

    private fun v1toV2(e: ActionEnvelope): ActionEnvelope? {
        if (e.tool != "phone_control") return e.copy(version = 2)
        val action = (e.args["action"] as? JsonPrimitive)?.contentOrNull ?: return null
        val level = (e.args["level"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }
        val (target, state) = when (action) {
            "volume_up" -> "volume" to "up"
            "volume_down" -> "volume" to "down"
            "volume_set" -> "volume" to null
            "mute" -> "volume" to "off"
            "unmute" -> "volume" to "on"
            "dnd_on" -> "dnd" to "on"
            "dnd_off" -> "dnd" to "off"
            "wifi_panel" -> "wifi" to null
            "bluetooth_panel" -> "bluetooth" to null
            else -> return null
        }
        if (action == "volume_set" && level == null) return null
        val args = buildJsonObject {
            put("target", target)
            state?.let { put("state", it) }
            if (action == "volume_set") put("level", level)
        }
        return ActionEnvelope("phone_control", args, version = 2)
    }
}
