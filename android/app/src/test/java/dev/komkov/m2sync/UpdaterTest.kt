package dev.komkov.m2sync

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdaterTest {
    @get:Rule
    val folder = TemporaryFolder()

    // --- разбор файлов релиза ---

    private fun assets(vararg names: String): JSONArray =
        JSONArray().apply {
            names.forEach { name ->
                put(
                    org.json
                        .JSONObject()
                        .put("name", name)
                        .put("browser_download_url", "https://example.invalid/$name"),
                )
            }
        }

    @Test
    fun `apk and its checksum are picked out of the release`() {
        val (apk, sha) = UpdateChecker.assets(assets("m2sync-v1.2.3.apk", "m2sync-v1.2.3.apk.sha256"))
        assertEquals("https://example.invalid/m2sync-v1.2.3.apk", apk)
        assertEquals("https://example.invalid/m2sync-v1.2.3.apk.sha256", sha)
    }

    /** Порядок файлов в ответе GitHub не обещан. */
    @Test
    fun `order in the release does not matter`() {
        val (apk, sha) = UpdateChecker.assets(assets("m2sync-v1.2.3.apk.sha256", "m2sync-v1.2.3.apk"))
        assertEquals("https://example.invalid/m2sync-v1.2.3.apk", apk)
        assertEquals("https://example.invalid/m2sync-v1.2.3.apk.sha256", sha)
    }

    /** Сумма заканчивается на .sha256 и за APK сойти не должна. */
    @Test
    fun `a checksum is never mistaken for the package`() {
        val (apk, sha) = UpdateChecker.assets(assets("m2sync-v1.2.3.apk.sha256"))
        assertNull(apk)
        assertEquals("https://example.invalid/m2sync-v1.2.3.apk.sha256", sha)
    }

    /** Старые релизы суммы не публиковали — это не повод терять APK. */
    @Test
    fun `a release without a checksum still yields the package`() {
        val (apk, sha) = UpdateChecker.assets(assets("m2sync-v1.2.3.apk", "notes.txt"))
        assertEquals("https://example.invalid/m2sync-v1.2.3.apk", apk)
        assertNull(sha)
    }

    @Test
    fun `a release without files yields nothing`() {
        assertEquals(null to null, UpdateChecker.assets(null))
        assertEquals(null to null, UpdateChecker.assets(assets("notes.txt")))
    }

    // --- контрольная сумма ---

    @Test
    fun `sha256 matches the known digest of an empty file`() {
        val file = folder.newFile("empty.apk")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Updater.sha256(file),
        )
    }

    @Test
    fun `sha256 matches the known digest of abc`() {
        val file = folder.newFile("abc.apk")
        file.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Updater.sha256(file),
        )
    }

    /** Файл длиннее буфера чтения: сумма не должна зависеть от нарезки на блоки. */
    @Test
    fun `sha256 survives a file larger than the read buffer`() {
        val file = folder.newFile("big.apk")
        file.writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        assertEquals(64, Updater.sha256(file).length)
        assertEquals(Updater.sha256(file), Updater.sha256(file))
    }

    // --- прогресс ---

    @Test
    fun `progress reports a fraction only when the length is known`() {
        assertNull(UpdateProgress(UpdateProgress.Stage.DOWNLOAD, 100, 0).fraction)
        assertEquals(0.5f, UpdateProgress(UpdateProgress.Stage.DOWNLOAD, 50, 100).fraction!!, 1e-6f)
    }

    /** Сервер может соврать про длину — доля всё равно обязана остаться в кадре. */
    @Test
    fun `progress never runs past one`() {
        assertEquals(1f, UpdateProgress(UpdateProgress.Stage.DOWNLOAD, 300, 100).fraction!!, 1e-6f)
    }
}
