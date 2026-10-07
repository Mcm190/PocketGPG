package com.pocketgpg.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentsTest {

    @Test
    fun `decrypted name drops the encryption suffix`() {
        assertEquals("notes.txt", Documents.decryptedName("notes.txt.gpg"))
        assertEquals("notes", Documents.decryptedName("notes.gpg"))
        assertEquals("notes", Documents.decryptedName("notes.ASC"))
        assertEquals("message.bin.decrypted", Documents.decryptedName("message.bin"))
    }

    @Test
    fun `an extension is appended to a name that has none`() {
        assertEquals("notes.txt", Documents.decryptedName("notes.gpg", "txt"))
        assertEquals("notes.txt", Documents.decryptedName("notes.gpg", ".txt"))
        assertEquals("notes.txt", Documents.decryptedName("notes.pgp", "txt."))
    }

    @Test
    fun `an extension is not doubled up`() {
        assertEquals("notes.txt", Documents.decryptedName("notes.txt.gpg", "txt"))
        assertEquals("notes.txt", Documents.decryptedName("notes.txt.gpg", "TXT"))
    }

    @Test
    fun `an extension never eats part of the existing name`() {
        assertEquals("data.2024.txt", Documents.decryptedName("data.2024.gpg", "txt"))
        assertEquals("report.pdf.txt", Documents.decryptedName("report.pdf.gpg", "txt"))
    }

    @Test
    fun `an extension on a file without an encryption suffix needs no decrypted marker`() {
        assertEquals("message.bin.txt", Documents.decryptedName("message.bin", "txt"))
    }

    @Test
    fun `a blank extension changes nothing`() {
        assertEquals("notes", Documents.decryptedName("notes.gpg", ""))
        assertEquals("notes", Documents.decryptedName("notes.gpg", "..."))
    }
}
