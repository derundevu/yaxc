package io.github.derundevu.yaxc.helper

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stage beside the destination so a failed write never damages the installed resource. */
internal inline fun <T> replaceFileAtomically(target: File, write: (File) -> T): T {
    val staged = File.createTempFile(".${target.name}.", ".tmp", target.absoluteFile.parentFile)
    try {
        val result = write(staged)
        Files.move(
            staged.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return result
    } finally {
        staged.delete()
    }
}
