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
package app.duvo.remote.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun comparesVersions() {
        assertTrue(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.1.0", "0.1.1"))
        assertTrue(UpdateChecker.isNewer("0.9", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
    }

    @Test fun parsesRelease() {
        val r = UpdateChecker.parseLatest(
            """{"tag_name":"v0.2.0","html_url":"https://github.com/x/y/releases/tag/v0.2.0",
               "assets":[{"name":"SHA256SUMS.txt","browser_download_url":"u1"},{"name":"app-release.apk","browser_download_url":"u2"}]}""",
        )!!
        assertEquals("0.2.0", r.version)
        assertEquals("u2", r.apkUrl)
        assertNull(UpdateChecker.parseLatest("not json"))
    }
}
