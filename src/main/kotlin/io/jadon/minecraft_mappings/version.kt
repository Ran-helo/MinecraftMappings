package io.jadon.minecraft_mappings

import com.google.common.collect.ImmutableList
import com.google.common.collect.MultimapBuilder
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.jadon.minecraft_mappings.provider.*
import net.techcable.srglib.FieldData
import net.techcable.srglib.JavaType
import net.techcable.srglib.MethodData
import net.techcable.srglib.format.MappingsFormat
import net.techcable.srglib.mappings.Mappings
import java.io.File

object MinecraftVersion {
    var mcVersion: String = "1.18.1"
    var mcpVersion: String? = null
    var mcpConfig: Boolean = false
    var spigot: Boolean = false

    fun generateMappings(): List<Pair<String, Mappings>> {
        // Mappings, fromObf
        val mappings = mutableListOf<Pair<Mappings, String>>()

        if (mcpVersion != null) {
            val obf2srgMappings = if (mcpConfig) {
                getMCPConfigMappings(mcVersion)
            } else {
                downloadSrgMappings(mcVersion)
            }
            val srg2mcpMappings = downloadMcpMappings(obf2srgMappings, mcpVersion!!, mcVersion)
            val obf2mcp = Mappings.chain(ImmutableList.of(obf2srgMappings, srg2mcpMappings))
            mappings.add(Pair(obf2srgMappings, "srg"))
            mappings.add(Pair(obf2mcp, "mcp"))
        }
        if (spigot) {
            val buildDataCommit = getBuildDataCommit(mcVersion)
            val obf2spigotMappings = downloadSpigotMappings(buildDataCommit)
            mappings.add(Pair(obf2spigotMappings, "spigot"))
        }

        val completeMappings = mutableListOf<Pair<String, Mappings>>()
        for (a in mappings) {
            val obf2aMappings = a.first
            val a2obfMappings = obf2aMappings.inverted()

            completeMappings.add(Pair("obf2${a.second}", obf2aMappings))
            completeMappings.add(Pair("${a.second}2obf", a2obfMappings))
            for (b in mappings) {
                if (a != b) {
                    val a2bMappings = Mappings.chain(a2obfMappings, b.first)
                    completeMappings.add(Pair("${a.second}2${b.second}", a2bMappings))
                }
            }
        }
        return completeMappings
    }

    @JvmStatic
    fun write(mcVersion: String, mcpVersion: String?, mcpConfig: Boolean, spigot: Boolean, mappingsFolder: File) {
        this.mcVersion = mcVersion
        this.mcpVersion = mcpVersion
        this.mcpConfig = mcpConfig
        this.spigot = spigot

        val outputFolder = File(mappingsFolder, mcVersion)
        outputFolder.mkdirs()

        fun Mappings.writeTo(fileName: String) {
            println("$mcVersion: writing mappings to $fileName.srg")
            val strippedMappings = stripDuplicates(this)
            val srgLines = MappingsFormat.SEARGE_FORMAT.toLines(strippedMappings)
            srgLines.sort()
            val file = File(outputFolder, "$fileName.srg")
            file.bufferedWriter().use {
                for (line in srgLines) {
                    it.write(line)
                    it.write("\n")
                }
            }

            println("$mcVersion: writing mappings to $fileName.csrg")
            val csrgLines = MappingsFormat.COMPACT_SEARGE_FORMAT.toLines(strippedMappings)
            csrgLines.sort()
            File(outputFolder, "$fileName.csrg").bufferedWriter().use {
                for (line in csrgLines) {
                    it.write(line)
                    it.write("\n")
                }
            }

            println("$mcVersion: writing mappings to $fileName.tsrg")
            TSrgUtil.fromSrg(file, File(outputFolder, "$fileName.tsrg"))
        }

        // srg & tsrg
        val generatedMappings = generateMappings()
        generatedMappings.forEach { pair ->
            val fileName = pair.first
            val mappings = pair.second
            mappings.writeTo(fileName)
        }

        // tiny
        println("$mcVersion: writing tiny mappings to $mcVersion.tiny")
        val tinyMappings = io.jadon.minecraft_mappings.tiny.Mappings()
        generatedMappings.filter { it.first.startsWith("obf2") }.forEach { pair ->
            val name = pair.first.split("2")[1]

            tinyMappings.addMappings(name, pair.second)
        }
        File(outputFolder, "$mcVersion.tiny").bufferedWriter().use {
            for (line in tinyMappings.toStrings()) {
                it.write(line)
                it.write("\n")
            }
        }

        // json
        val classMappings =
            MultimapBuilder.hashKeys(1000).arrayListValues().build<JavaType, Pair<String, JavaType>>()
        val fieldMappings =
            MultimapBuilder.hashKeys(1000).arrayListValues().build<FieldData, Pair<String, FieldData>>()
        val methodMappings =
            MultimapBuilder.hashKeys(1000).arrayListValues().build<MethodData, Pair<String, MethodData>>()
        generatedMappings.filter { it.first.startsWith("obf2") }.forEach { pair ->
            val name = pair.first.split("2")[1]
            val mappings = pair.second
            mappings.forEachClass { obf, mapped -> classMappings.put(obf, Pair(name, mapped)) }
            mappings.forEachField { obf, mapped -> fieldMappings.put(obf, Pair(name, mapped)) }
            mappings.forEachMethod { obf, mapped -> methodMappings.put(obf, Pair(name, mapped)) }
            println("$mcVersion: generating json for $name")
        }

        fun String.lp(): String = split(".").last()

        val classArray = JsonArray()
        val fieldArray = JsonArray()
        val methodArray = JsonArray()
        for (obf in classMappings.keySet()) {
            val mappedObj = JsonObject()
            mappedObj.addProperty("obf", obf.name.lp())
            classMappings.get(obf).forEach {
                mappedObj.addProperty(it.first, it.second.name.lp())
            }
            classArray.add(mappedObj)
        }
        for (obf in fieldMappings.keySet()) {
            val mappedObj = JsonObject()
            mappedObj.addProperty("obf", obf.declaringType.name.lp() + "." + obf.name.lp())
            fieldMappings.get(obf).forEach {
                mappedObj.addProperty(it.first, it.second.declaringType.name.lp() + "." + it.second.name)
            }
            fieldArray.add(mappedObj)
        }
        for (obf in methodMappings.keySet()) {
            val mappedObj = JsonObject()
            mappedObj.addProperty("obf", obf.declaringType.name.lp() + "." + obf.name.lp())
            methodMappings.get(obf).forEach {
                mappedObj.addProperty(it.first, it.second.declaringType.name.lp() + "." + it.second.name)
            }
            methodArray.add(mappedObj)
        }

        val bigJson = JsonObject()
        bigJson.addProperty("minecraftVersion", mcVersion)
        bigJson.add("classes", classArray)
        bigJson.add("fields", fieldArray)
        bigJson.add("methods", methodArray)
        File(outputFolder, "$mcVersion.json").writeText(Gson().toJson(bigJson))
    }
}
