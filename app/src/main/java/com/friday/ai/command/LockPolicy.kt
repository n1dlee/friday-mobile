package com.friday.ai.command

import com.friday.ai.domain.model.CommandResult

/**
 * What a locked phone lets through.
 *
 * The wake word works with the screen locked, so anyone in the room can say
 * "Пятница". With the owner's voice profile recorded (one that checks
 * commands, not only the wake word), what they say next is verified, and
 * Friday does it. Without one, nothing confirms it's the owner: then
 * whatever reaches other people, or reads out what they wrote — calls,
 * messages, replies, mail, missed notifications — waits until the phone is
 * unlocked. Music, alarms, the flashlight and modes stay available: they
 * change only the phone itself.
 */
object LockPolicy {

    /** Whether [command] must wait for the phone to be unlocked. */
    fun needsUnlock(command: CommandResult, locked: Boolean, ownerVerified: Boolean): Boolean =
        locked && !ownerVerified && personal(command)

    /** Reaches other people, or reads out what they wrote. */
    fun personal(command: CommandResult): Boolean = when (command) {
        is CommandResult.PhoneCall, is CommandResult.SendMessage, is CommandResult.ReplyMessage,
        is CommandResult.ReadMessages, is CommandResult.Mail, CommandResult.WhatDidIMiss -> true
        is CommandResult.Sequence -> command.steps.any(::personal)
        else -> false
    }

    fun reply(russian: Boolean): String =
        if (russian) {
            "Телефон заблокирован, а голосовой профиль не записан — я не знаю, что это вы. " +
                "Разблокируйте телефон и повторите."
        } else {
            "The phone is locked and no voice profile is recorded, so I can't tell it's you. " +
                "Unlock it and say it again."
        }
}
