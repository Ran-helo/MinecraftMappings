package io.jadon.minecraft_mappings.provider

import com.google.common.collect.ImmutableBiMap
import com.google.common.collect.ImmutableList
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.opencsv.CSVReader
import net.techcable.srglib.FieldData
import net.techcable.srglib.JavaType
import net.techcable.srglib.MethodData
import net.techcable.srglib.format.MappingsFormat
import net.techcable.srglib.mappings.ImmutableMappings
import net.techcable.srglib.mappings.Mappings
import java.io.File
import java.io.Reader
import java.net.URL
import java.util.*

/**
 * Thank you Techcable for being awesome <3
 * @author Techcable (MIT License)
 */

inline fun <reified T> Gson.fromJson(reader: Reader): T = this.fromJson<T>(reader, (object : TypeToken<T>() {}).type)

inline fun <reified T> Gson.fromJson(element: JsonElement): T =
    this.fromJson<T>(element, (object : TypeToken<T>() {}).type)

inline fun CSVReader.forEachLine(action: (Array<String>) -> Unit) {
    var line = this.readNext()
    while (line != null) {
        action(line)
        line = this.readNext()
    }
}

fun URL.loadJson(): JsonElement = this.openStream().reader().use { JsonParser().parse(it) }
fun URL.downloadTo(target: File) {
    check(target.createNewFile()) { "Unable to download ${this} to $target: File already exists" }
    this.openStream().use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun JsonObject.set(key: String, value: JsonElement) = this.add(key, value)

fun stripDuplicates(mappings: Mappings): ImmutableMappings {
    val classes = HashMap<JavaType, JavaType>()
    val fields = HashMap<FieldData, FieldData>()
    val methods = HashMap<MethodData, MethodData>()
    mappings.forEachClass { original, renamed ->
        if (original != renamed) {
            classes[original] = renamed
        }
    }
    mappings.forEachField { original, renamed ->
        if (original.name != renamed.name) {
            fields[original] = renamed
        }
    }
    mappings.forEachMethod { original, renamed ->
        if (original.name != renamed.name) {
            methods[original] = renamed
        }
    }
    return ImmutableMappings.create(
        ImmutableBiMap.copyOf(classes),
        ImmutableBiMap.copyOf(methods),
        ImmutableBiMap.copyOf(fields)
    )
}

class CacheInfo(val buildDataCommits: MutableMap<String, String> = HashMap()) {
    fun saveTo(file: File) = file.writer().use { Gson().toJson(this, it) }

    companion object {
        fun loadFrom(file: File) = file.reader().use { Gson().fromJson<CacheInfo>(it) }
    }
}

/**
 * Get the build data commit from the specified spigot revision
 *
 * The Spigot revision is specified using '--rev=1.8' as a buildtools option
 * Available revisions: https://hub.spigotmc.org/versions/
 * @param spigotVersion the build data revision
 * @return the build data commit-id for this revision
 */
fun getBuildDataCommit(spigotVersion: String): String {
    val cacheFile = File("cache/info.json")
    val cacheInfo = if (cacheFile.exists()) CacheInfo.loadFrom(cacheFile) else CacheInfo()
    return cacheInfo.buildDataCommits.getOrElse(spigotVersion) {
        val url = URL("https://hub.spigotmc.org/versions/$spigotVersion.json")
        val json = url.loadJson().asJsonObject
        val buildDataCommit = json.getAsJsonObject("refs").get("BuildData").asString
        // Store it in the cache
        cacheInfo.buildDataCommits[spigotVersion] = buildDataCommit
        cacheInfo.saveTo(cacheFile)
        buildDataCommit
    }
}

/**
 * Syntax errors in the srg files that SpecialSource swallows silently
 */
val brokenLines = setOf(
    "IDispenseBehavior a(LISourceBlock;LItemStack;)LItemStack; dispense",
    "nv ServerStatisticManager#",
    "ql ServerStatisticManager#",
    "qn ServerStatisticManager#"
)

fun stripBrokenLines(lines: List<String>) = lines.filter { it !in brokenLines && "<init>" !in it }

fun downloadSpigotMappings(buildDataCommit: String): Mappings {
    val baseUrl = "https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/browse/"
    val cacheDir = File("cache/spigot_$buildDataCommit")
    val classMappingsFile = File(cacheDir, "classes.csrg")
    val memberMappingsFile = File(cacheDir, "members.csrg")
    if (!classMappingsFile.exists() || !memberMappingsFile.exists()) {
        cacheDir.mkdirs()
        val info = URL("$baseUrl/info.json?at=$buildDataCommit&raw").loadJson().asJsonObject
        val classMappingsLocation = info.get("classMappings").asString
        val memberMappings = info.get("memberMappings")
        if (memberMappings != null) {
            val memberMappingsLocation = memberMappings.asString
            if (!memberMappingsFile.exists()) {
                URL("$baseUrl/mappings/$memberMappingsLocation/?at=$buildDataCommit&raw").downloadTo(memberMappingsFile)
            }
        } else {
            if (!memberMappingsFile.exists()) {
                // TODO: Make this better
                URL("https://maven.elmakers.com/repository/org/spigotmc/minecraft-server/" + info.get("spigotVersion").asString + "/minecraft-server-" + info.get("spigotVersion").asString + "-maps-spigot-members.csrg").downloadTo(memberMappingsFile)
            }
        }
        if (!classMappingsFile.exists()) {
            URL("$baseUrl/mappings/$classMappingsLocation/?at=$buildDataCommit&raw").downloadTo(classMappingsFile)
        }
    }
    val classMappings = MappingsFormat.COMPACT_SEARGE_FORMAT.parseLines(stripBrokenLines(classMappingsFile.readLines()))
    val memberMappings =
        MappingsFormat.COMPACT_SEARGE_FORMAT.parseLines(stripBrokenLines(memberMappingsFile.readLines()))
    return Mappings.chain(ImmutableList.of(classMappings, memberMappings))
}
