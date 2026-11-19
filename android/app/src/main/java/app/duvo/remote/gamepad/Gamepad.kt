/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.duvo.remote.gamepad

import app.duvo.remote.stream.NativeBridge

/** Moonlight button flags (Limelight.h), Xbox naming. */
object Btn {
    const val UP = 0x0001
    const val DOWN = 0x0002
    const val LEFT = 0x0004
    const val RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LS = 0x0040
    const val RS = 0x0080
    const val LB = 0x0100
    const val RB = 0x0200
    const val GUIDE = 0x0400
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
    const val TOUCHPAD = 0x100000
    const val MISC = 0x200000

    /** Every button a standard pad (and our virtual pad) can report. */
    const val STANDARD = UP or DOWN or LEFT or RIGHT or START or BACK or LS or RS or LB or RB or GUIDE or A or B or X or Y
}

/** One gamepad snapshot: triggers 0..255, sticks -32768..32767 with up = positive Y (Xinput). */
data class GamepadState(
    val buttons: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val lx: Short = 0,
    val ly: Short = 0,
    val rx: Short = 0,
    val ry: Short = 0,
)

/** Emulated pad on the PC: X360 for Xbox/Switch styles, DS4 for PlayStation (host picks from the arrival type). */
enum class PadStyle(val label: String, val ctype: Int) {
    XBOX("Estilo Xbox", NativeBridge.CTYPE_XBOX),
    PLAYSTATION("Estilo PlayStation", NativeBridge.CTYPE_PS),
    SWITCH("Estilo Switch", NativeBridge.CTYPE_XBOX),
}

/**
 * Sends gamepads to the host: one arrival per slot, then state only when it changes. Slots 0..3; the
 * active mask tells the host which pads exist (removing a pad = sending the mask without its bit).
 */
class ControllerSender(private val bridge: NativeBridge) {
    private val last = arrayOfNulls<GamepadState>(MAX)
    private val types = IntArray(MAX)
    private var mask = 0

    val activeMask: Int @Synchronized get() = mask

    @Synchronized fun connect(slot: Int, ctype: Int, capabilities: Int) {
        require(slot in 0 until MAX)
        mask = mask or (1 shl slot)
        types[slot] = ctype
        last[slot] = null
        bridge.sendControllerArrival(slot, mask, ctype, Btn.STANDARD, capabilities)
        update(slot, GamepadState())
    }

    @Synchronized fun disconnect(slot: Int) {
        if (mask and (1 shl slot) == 0) return
        mask = mask and (1 shl slot).inv()
        last[slot] = null
        bridge.sendController(slot, mask, 0, 0, 0, 0, 0, 0, 0)
    }

    @Synchronized fun update(slot: Int, s: GamepadState) {
        if (mask and (1 shl slot) == 0 || last[slot] == s) return
        last[slot] = s
        bridge.sendController(slot, mask, s.buttons, s.leftTrigger, s.rightTrigger, s.lx, s.ly, s.rx, s.ry)
    }

    @Synchronized fun firstFreeSlot(): Int? = (0 until MAX).firstOrNull { mask and (1 shl it) == 0 }

    /** Resends arrivals after a reconnect (the host forgot the pads). */
    @Synchronized fun resendAll(capabilities: (Int) -> Int) {
        val m = mask
        for (slot in 0 until MAX) if (m and (1 shl slot) != 0) {
            last[slot] = null
            bridge.sendControllerArrival(slot, m, types[slot], Btn.STANDARD, capabilities(slot))
        }
    }

    companion object { const val MAX = 4 }
}
