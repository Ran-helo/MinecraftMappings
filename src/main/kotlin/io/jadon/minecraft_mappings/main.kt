package io.jadon.minecraft_mappings

import java.io.File

val GLOBAL_FOLDER = File("mappings")

fun main() {
    val time = System.currentTimeMillis()
    GLOBAL_FOLDER.mkdirs()
    MinecraftVersion.write("1.18.1", null, false, true, GLOBAL_FOLDER)
    val elapsed = (System.currentTimeMillis() - time) / 1000.0
    println("Done. Took ${elapsed / 60}m (${elapsed}s)")
}
