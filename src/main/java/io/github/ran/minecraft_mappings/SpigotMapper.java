package io.github.ran.minecraft_mappings;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code from https://github.com/DragonCommissions/MixBukkit (https://github.com/DragonCommissions/MixBukkit/blob/2f3a678e557704fd90796a43754dceb899db8252/src/main/java/com/dragoncommissions/mixbukkit/addons/AutoMapper.java)
 * It has been modified to fit the needs of this project.
 */
public class SpigotMapper {
    private final File mappingFile;
    private final File clazzMappingFile;

    public SpigotMapper(File classMappingsFile, File memberMappingsFile) {
        clazzMappingFile = classMappingsFile;
        mappingFile = memberMappingsFile;
    }

    public File getMemberMappingsFile() {
        return mappingFile;
    }

    public File getClassMappingsFile() {
        return clazzMappingFile;
    }

    public SpigotMapper prepareMapping(String mcVersion) throws IOException, GitAPIException {
        if (mappingFile.exists()) {
            if (!mappingFile.isDirectory()) {
                return this;
            }
            mappingFile.delete();
        }
        System.out.println("Preparing mappings...");
        File buildDataDir = new File(System.getProperty("user.home"), "BuildData");
        System.out.println("Fetching BuildData...");
        Gson gson = new Gson();
        URLConnection connection = new URL("https://hub.spigotmc.org/versions/" + mcVersion + ".json").openConnection();
        JsonObject object = gson.fromJson(new InputStreamReader(connection.getInputStream()), JsonObject.class);
        String buildDataVersion = object.get("refs").getAsJsonObject().get("BuildData").getAsString();
        Git buildData = null;
        System.out.println("Cloning BuildData...");
        if (buildDataDir.exists()) {
            try {
                buildData = Git.open(buildDataDir);
                buildData.pull().call();
            } catch (Exception e) {
                buildDataDir.delete();
            }
        }
        if (!buildDataDir.exists()) {
            buildData = Git.cloneRepository().setURI("https://hub.spigotmc.org/stash/scm/spigot/builddata.git").setDirectory(buildDataDir).call();
        }
        System.out.println("Successfully fetched BuildData!");
        buildData.checkout().setName(buildDataVersion).call();
        VersionInfo versionInfo = gson.fromJson(new FileReader(new File(buildDataDir, "info.json")), VersionInfo.class);
        File classMappings = new File(buildDataDir, "mappings/" + versionInfo.classMappings);
        System.out.println("Fetching spigot mappings...");
        if (versionInfo.memberMappings == null) {
            MapUtil mapUtil = new MapUtil();
            mapUtil.loadBuk(classMappings);
            InputStream inputStream = new URL(versionInfo.mappingsUrl).openConnection().getInputStream();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            while (true) {
                int read = inputStream.read();
                if (read == -1) break;
                outputStream.write(read);
            }
            mapUtil.makeFieldMaps(new String(outputStream.toByteArray()), mappingFile, true);
        } else {
            mappingFile.createNewFile();
            Files.copy(classMappings.toPath(), clazzMappingFile.toPath());
            Files.copy(new File(buildDataDir, "mappings/" + versionInfo.memberMappings).toPath(), mappingFile.toPath());
        }
        System.out.println("Successfully fetched spigot mappings!");
        return this;
    }

    /**
     * Source: Spigot BuildTools
     */
    private class VersionInfo {

        private String minecraftVersion;
        private String accessTransforms;
        private String classMappings;
        private String memberMappings;
        private String packageMappings;
        private String minecraftHash;
        private String classMapCommand;
        private String memberMapCommand;
        private String finalMapCommand;
        private String decompileCommand;
        private String serverUrl;
        private String mappingsUrl;
        private String spigotVersion;
        private int toolsVersion = -1;

        public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings, String packageMappings, String minecraftHash) {
            this.minecraftVersion = minecraftVersion;
            this.accessTransforms = accessTransforms;
            this.classMappings = classMappings;
            this.memberMappings = memberMappings;
            this.packageMappings = packageMappings;
            this.minecraftHash = minecraftHash;
        }

        public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings, String packageMappings, String minecraftHash, String decompileCommand) {
            this.minecraftVersion = minecraftVersion;
            this.accessTransforms = accessTransforms;
            this.classMappings = classMappings;
            this.memberMappings = memberMappings;
            this.packageMappings = packageMappings;
            this.minecraftHash = minecraftHash;
            this.decompileCommand = decompileCommand;
        }

        public String getShaServerHash() {
            return hashFromUrl(serverUrl);
        }

        public String getShaMappingsHash() {
            return hashFromUrl(mappingsUrl);
        }

        private transient final Pattern URL_PATTERN = Pattern.compile("https://launcher.mojang.com/v1/objects/([0-9a-f]{40})/.*");

        public String hashFromUrl(String url) {
            if (url == null) {
                return null;
            }

            Matcher match = URL_PATTERN.matcher(url);
            return (match.find()) ? match.group(1) : null;
        }
    }

    private class MapUtil {

        private final Pattern MEMBER_PATTERN = Pattern.compile("(?:\\d+:\\d+:)?(.*?) (.*?) \\-> (.*)");
        //
        private List<String> header = new ArrayList<>();
        private final BiMap<String, String> obf2Buk = HashBiMap.create();
        private final BiMap<String, String> moj2Obf = HashBiMap.create();

        public void loadBuk(File bukClasses) throws IOException {
            for (String line : Files.readAllLines(bukClasses.toPath())) {
                if (line.startsWith("#")) {
                    header.add(line);
                    continue;
                }

                String[] split = line.split(" ");
                if (split.length == 2) {
                    obf2Buk.put(split[0], split[1]);
                }
            }
        }

        public void makeFieldMaps(String mojIn, File fields, boolean includeMethods) throws IOException {
            List<String> lines = new ArrayList<>();
            if (includeMethods) {
                for (String line : mojIn.split("\n")) {
                    lines.add(line);
                    if (line.startsWith("#")) {
                        continue;
                    }

                    if (line.endsWith(":")) {
                        String[] parts = line.split(" -> ");
                        String orig = parts[0].replace('.', '/');
                        String obf = parts[1].substring(0, parts[1].length() - 1).replace('.', '/');

                        moj2Obf.put(orig, obf);
                    }
                }
            }

            List<String> outFields = new ArrayList<>(header);

            String currentClass = null;
            outer:
            for (String line : mojIn.split("\n")) {
                if (line.startsWith("#")) {
                    continue;
                }
                line = line.trim();

                if (line.endsWith(":")) {
                    currentClass = null;

                    String[] parts = line.split(" -> ");
                    String orig = parts[0].replace('.', '/');
                    String obf = parts[1].substring(0, parts[1].length() - 1).replace('.', '/');

                    String buk = deobfClass(obf, obf2Buk);
                    if (buk == null) {
                        continue;
                    }

                    currentClass = buk;
                } else if (currentClass != null) {
                    Matcher matcher = MEMBER_PATTERN.matcher(line);
                    matcher.find();

                    String obf = matcher.group(3);
                    String nameDesc = matcher.group(2);
                    if (!nameDesc.contains("(")) {
                        if (nameDesc.equals(obf) || nameDesc.contains("$")) {
                            continue;
                        }
                        if (!includeMethods && (obf.equals("if") || obf.equals("do"))) {
                            obf += "_";
                        }

                        outFields.add(currentClass + " " + obf + " " + nameDesc);
                    } else if (includeMethods) {
                        String sig = csrgDesc(moj2Obf, obf2Buk, nameDesc.substring(nameDesc.indexOf('(')), matcher.group(1));
                        String mojName = nameDesc.substring(0, nameDesc.indexOf('('));

                        if (obf.equals(mojName) || mojName.contains("$") || obf.equals("<init>") || obf.equals("<clinit>")) {
                            continue;
                        }
                        outFields.add(currentClass + " " + obf + " " + sig + " " + mojName);
                    }
                }
            }

            Collections.sort(outFields);
            fields.createNewFile();
            Files.write(fields.toPath(), outFields);
        }

        public void makeCombinedMaps(File out, File... members) throws IOException {
            List<String> combined = new ArrayList<>(header);

            for (Map.Entry<String, String> map : obf2Buk.entrySet()) {
                combined.add(map.getKey() + " " + map.getValue());
            }

            for (File member : members) {
                for (String line : Files.readAllLines(member.toPath())) {
                    if (line.startsWith("#")) {
                        continue;
                    }
                    line = line.trim();

                    String[] split = line.split(" ");
                    if (split.length == 3) {
                        String clazz = split[0];
                        String orig = split[1];
                        String targ = split[2];

                        combined.add(deobfClass(clazz, obf2Buk.inverse()) + " " + orig + " " + targ);
                    } else if (split.length == 4) {
                        String clazz = split[0];
                        String orig = split[1];
                        String desc = split[2];
                        String targ = split[3];

                        combined.add(deobfClass(clazz, obf2Buk.inverse()) + " " + orig + " " + toObf(desc, obf2Buk.inverse()) + " " + targ);
                    }
                }
            }

            Files.write(out.toPath(), combined);
        }

        public String deobfClass(String obf, Map<String, String> classMaps) {
            String buk = classMaps.get(obf);
            if (buk == null) {
                StringBuilder inner = new StringBuilder();

                while (buk == null) {
                    int idx = obf.lastIndexOf('$');
                    if (idx == -1) {
                        return null;
                    }
                    inner.insert(0, obf.substring(idx));
                    obf = obf.substring(0, idx);

                    buk = classMaps.get(obf);
                }

                buk += inner;
            }
            return buk;
        }

        public String toObf(String desc, Map<String, String> map) {
            desc = desc.substring(1);
            StringBuilder out = new StringBuilder("(");
            if (desc.charAt(0) == ')') {
                desc = desc.substring(1);
                out.append(')');
            }
            while (desc.length() > 0) {
                desc = obfType(desc, map, out);
                if (desc.length() > 0 && desc.charAt(0) == ')') {
                    desc = desc.substring(1);
                    out.append(')');
                }
            }
            return out.toString();
        }

        public String obfType(String desc, Map<String, String> map, StringBuilder out) {
            int size = 1;
            switch (desc.charAt(0)) {
                case 'B':
                case 'C':
                case 'D':
                case 'F':
                case 'I':
                case 'J':
                case 'S':
                case 'Z':
                case 'V':
                    out.append(desc.charAt(0));
                    break;
                case '[':
                    out.append("[");
                    return obfType(desc.substring(1), map, out);
                case 'L':
                    String type = desc.substring(1, desc.indexOf(";"));
                    size += type.length() + 1;
                    out.append("L").append(map.containsKey(type) ? map.get(type) : type).append(";");
            }
            return desc.substring(size);
        }

        private String csrgDesc(Map<String, String> first, Map<String, String> second, String args, String ret) {
            String[] parts = args.substring(1, args.length() - 1).split(",");
            StringBuilder desc = new StringBuilder("(");
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                desc.append(toJVMType(first, second, part));
            }
            desc.append(")");
            desc.append(toJVMType(first, second, ret));
            return desc.toString();
        }

        private String toJVMType(Map<String, String> first, Map<String, String> second, String type) {
            switch (type) {
                case "byte":
                    return "B";
                case "char":
                    return "C";
                case "double":
                    return "D";
                case "float":
                    return "F";
                case "int":
                    return "I";
                case "long":
                    return "J";
                case "short":
                    return "S";
                case "boolean":
                    return "Z";
                case "void":
                    return "V";
                default:
                    if (type.endsWith("[]")) {
                        return "[" + toJVMType(first, second, type.substring(0, type.length() - 2));
                    }
                    String clazzType = type.replace('.', '/');
                    String obf = deobfClass(clazzType, first);
                    String mappedType = deobfClass((obf != null) ? obf : clazzType, second);

                    return "L" + ((mappedType != null) ? mappedType : clazzType) + ";";
            }
        }
    }
}
