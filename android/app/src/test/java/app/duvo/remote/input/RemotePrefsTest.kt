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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemotePrefsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file() = File(tmp.root, "prefs.json")

    @Test fun `ordem padrao`() {
        val p = RemotePrefs(file())
        assertEquals(listOf(PanelId.KEYBOARD, PanelId.MEDIA, PanelId.BROWSER, PanelId.PRESENTATION, PanelId.APPS, PanelId.POWER), p.panelOrder)
        assertEquals(emptySet<PanelId>(), p.hidden)
        assertEquals(PadConfig(), p.padConfig)
        assertTrue(p.haptics); assertFalse(p.tapSound); assertTrue(p.volumeButtonsControlPc); assertFalse(p.autoClipboardOnResume)
    }

    @Test fun `ordem e ocultos persistem`() {
        val p = RemotePrefs(file())
        val order = listOf(PanelId.MEDIA, PanelId.KEYBOARD, PanelId.BROWSER, PanelId.PRESENTATION, PanelId.APPS, PanelId.POWER)
        p.panelOrder = order
        p.hidden = setOf(PanelId.BROWSER)
        p.haptics = false; p.tapSound = true; p.volumeButtonsControlPc = false; p.autoClipboardOnResume = true
        val cfg = PadConfig(2f, false, true, 9f, 250, 1200, 70f, 50f, 90f)
        p.padConfig = cfg
        val q = RemotePrefs(file())
        assertEquals(order, q.panelOrder)
        assertEquals(setOf(PanelId.BROWSER), q.hidden)
        assertEquals(cfg, q.padConfig)
        assertFalse(q.haptics); assertTrue(q.tapSound); assertFalse(q.volumeButtonsControlPc); assertTrue(q.autoClipboardOnResume)
    }

    @Test fun `arquivo corrompido volta ao padrao sem apagar`() {
        file().writeText("{not json")
        val p = RemotePrefs(file())
        assertEquals(PanelId.values().toList(), p.panelOrder)
        assertTrue(file().exists())
        assertEquals("{not json", file().readText())
    }

    @Test fun `ordem incompleta e completada`() {
        file().writeText("""{"panelOrder":["POWER","BOGUS","MEDIA"]}""")
        val p = RemotePrefs(file())
        assertEquals(listOf(PanelId.POWER, PanelId.MEDIA, PanelId.KEYBOARD, PanelId.BROWSER, PanelId.PRESENTATION, PanelId.APPS), p.panelOrder)
    }

    @Test fun `valores invalidos no arquivo viram padrao`() {
        file().writeText("""{"pad":{"sensitivity":-1,"dragLockMs":-5,"scrollPxPerNotch":0,"swipePx":"NaN","tapMaxMs":300}}""")
        val p = RemotePrefs(file()).padConfig
        val d = PadConfig()
        assertEquals(d.sensitivity, p.sensitivity)
        assertEquals(d.dragLockMs, p.dragLockMs)
        assertEquals(d.scrollPxPerNotch, p.scrollPxPerNotch)
        assertEquals(d.swipePx, p.swipePx)
        assertEquals(300L, p.tapMaxMs)
    }

    @Test fun `setter com NaN nao lanca e guarda padrao`() {
        val p = RemotePrefs(file())
        p.padConfig = PadConfig(sensitivity = Float.NaN, pinchPxPerStep = Float.POSITIVE_INFINITY, tapSlopPx = -1f)
        assertEquals(PadConfig().sensitivity, p.padConfig.sensitivity)
        assertEquals(PadConfig().pinchPxPerStep, p.padConfig.pinchPxPerStep)
        assertEquals(PadConfig().tapSlopPx, p.padConfig.tapSlopPx)
        assertEquals(PadConfig(), RemotePrefs(file()).padConfig)
    }
}
