package ember.engine

import java.io.File

/** Per-user folder for the session file and the server-list cache. */
fun appDataDir(): File {
    val os = System.getProperty("os.name").orEmpty()
    val base = when {
        os.startsWith("Windows", ignoreCase = true) -> System.getenv("APPDATA")?.let(::File)
        os.startsWith("Mac", ignoreCase = true) -> File(System.getProperty("user.home"), "Library/Application Support")
        else -> System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
    } ?: File(System.getProperty("user.home"))
    return File(base, "Ember").also { it.mkdirs() }
}
