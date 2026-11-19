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
package app.duvo.remote.input

import app.duvo.remote.stream.NativeBridge

/** Windows virtual-key codes. */
object Vk {
    const val BACK: Short = 0x08
    const val TAB: Short = 0x09
    const val ENTER: Short = 0x0D
    const val SHIFT: Short = 0x10
    const val CONTROL: Short = 0x11
    const val MENU: Short = 0x12
    const val ESC: Short = 0x1B
    const val PGUP: Short = 0x21
    const val PGDN: Short = 0x22
    const val END: Short = 0x23
    const val HOME: Short = 0x24
    const val LEFT: Short = 0x25
    const val UP: Short = 0x26
    const val RIGHT: Short = 0x27
    const val DOWN: Short = 0x28
    const val SNAPSHOT: Short = 0x2C
    const val DEL: Short = 0x2E
    const val LWIN: Short = 0x5B
    const val BROWSER_BACK: Short = 0xA6
    const val BROWSER_FWD: Short = 0xA7
    const val BROWSER_REFRESH: Short = 0xA8
    const val VOL_MUTE: Short = 0xAD
    const val VOL_DOWN: Short = 0xAE
    const val VOL_UP: Short = 0xAF
    const val MEDIA_NEXT: Short = 0xB0
    const val MEDIA_PREV: Short = 0xB1
    const val MEDIA_STOP: Short = 0xB2
    const val MEDIA_PLAY_PAUSE: Short = 0xB3
    const val OEM_PLUS: Short = 0xBB
    const val OEM_MINUS: Short = 0xBD

    /**
     * Key flags for [vk]. Browser/volume/media keys have no US-layout scan code, so they are sent
     * non-normalized and the host uses the virtual-key code itself (otherwise Windows got a letter).
     */
    fun flagsFor(vk: Short): Byte =
        if (vk in BROWSER_BACK..MEDIA_PLAY_PAUSE) NativeBridge.FLAG_NON_NORMALIZED else 0

    const val N0: Short = 0x30
    const val N1: Short = 0x31
    const val N2: Short = 0x32
    const val N3: Short = 0x33
    const val N4: Short = 0x34
    const val N5: Short = 0x35
    const val N6: Short = 0x36
    const val N7: Short = 0x37
    const val N8: Short = 0x38
    const val N9: Short = 0x39
    const val A: Short = 0x41
    const val B: Short = 0x42
    const val C: Short = 0x43
    const val D: Short = 0x44
    const val E: Short = 0x45
    const val F: Short = 0x46
    const val G: Short = 0x47
    const val H: Short = 0x48
    const val I: Short = 0x49
    const val J: Short = 0x4A
    const val K: Short = 0x4B
    const val L: Short = 0x4C
    const val M: Short = 0x4D
    const val N: Short = 0x4E
    const val O: Short = 0x4F
    const val P: Short = 0x50
    const val Q: Short = 0x51
    const val R: Short = 0x52
    const val S: Short = 0x53
    const val T: Short = 0x54
    const val U: Short = 0x55
    const val V: Short = 0x56
    const val W: Short = 0x57
    const val X: Short = 0x58
    const val Y: Short = 0x59
    const val Z: Short = 0x5A


    const val F1: Short = 0x70
    const val F2: Short = 0x71
    const val F3: Short = 0x72
    const val F4: Short = 0x73
    const val F5: Short = 0x74
    const val F6: Short = 0x75
    const val F7: Short = 0x76
    const val F8: Short = 0x77
    const val F9: Short = 0x78
    const val F10: Short = 0x79
    const val F11: Short = 0x7A
    const val F12: Short = 0x7B
    const val F24: Short = 0x87
}

data class KeyEvent(val vk: Short, val down: Boolean, val modifiers: Byte)

private val MOD_ORDER: List<Pair<Byte, Short>> = listOf(
    NativeBridge.MOD_CTRL to Vk.CONTROL,
    NativeBridge.MOD_SHIFT to Vk.SHIFT,
    NativeBridge.MOD_ALT to Vk.MENU,
    NativeBridge.MOD_META to Vk.LWIN,
)

/** Modifier downs (CTRL, SHIFT, ALT, META), each key down/up, then modifier ups in reverse. Every event carries the full [mods] mask. */
fun chordEvents(keys: List<Short>, mods: Byte): List<KeyEvent> {
    val active = MOD_ORDER.filter { (it.first.toInt() and mods.toInt()) != 0 }.map { it.second }
    val out = ArrayList<KeyEvent>()
    active.forEach { out += KeyEvent(it, true, mods) }
    keys.forEach {
        out += KeyEvent(it, true, mods)
        out += KeyEvent(it, false, mods)
    }
    active.asReversed().forEach { out += KeyEvent(it, false, mods) }
    return out
}
