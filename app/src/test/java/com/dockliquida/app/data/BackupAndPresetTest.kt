package com.dockliquida.app.data

import java.io.Reader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupAndPresetTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun keepsNarrowPanoramasAndSmallPhotosAtValidDimensions() {
        assertEquals(1 to 360, thumbnailSize(1, 719))
        assertEquals(360 to 1, thumbnailSize(719, 1))
        assertEquals(120 to 180, thumbnailSize(120, 180))
        assertEquals(202 to 360, thumbnailSize(720, 1280))
    }

    @Test fun acceptsMainPhotoAloneOrWithSeparateLockPhoto() {
        files.newFile("home").writeText("foto")
        files.newFile("lock_photo").writeText("foto")
        for (ids in listOf(emptySet(), setOf("home"), setOf("home", "lock_photo"))) {
            val checked = mutableSetOf<String>()
            verifyBackupImages(files.root, ids) { checked.add(it.name) }
            assertEquals(ids, checked)
        }
    }

    @Test fun rejectsMissingMainAndMissingLockPhotos() {
        files.newFile("present").writeText("foto")
        for (ids in listOf(setOf("missing"), setOf("present", "lock_missing"))) {
            assertThrows(IllegalArgumentException::class.java) {
                verifyBackupImages(files.root, ids) { true }
            }
        }
    }

    @Test fun rejectsEmptyFilesAndUnreadableHeaders() {
        files.newFile("empty")
        files.newFile("unreadable").writeText("foto")
        assertThrows(IllegalArgumentException::class.java) {
            verifyBackupImages(files.root, setOf("empty")) { true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyBackupImages(files.root, setOf("unreadable")) { false }
        }
    }

    @Test fun acceptsPresetAtOriginalCharacterLimit() {
        val source = "á".repeat(64_000)
        assertEquals(source, readDockPresetText(source.reader()))
    }

    @Test fun stopsAfterFirstExcessCharacterEvenWhenMoreInputIsAvailable() {
        var consumed = 0
        val reader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                val count = minOf(length, 311)
                buffer.fill('x', offset, offset + count)
                consumed += count
                check(consumed <= 64_001)
                return count
            }
            override fun close() = Unit
        }
        assertThrows(IllegalArgumentException::class.java) { readDockPresetText(reader) }
        assertEquals(64_001, consumed)
    }
}
