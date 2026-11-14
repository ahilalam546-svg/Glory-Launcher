package io.github.juanluci.starling;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure-Java data layer for the Glory Launcher dashboard. Reads and writes Starling/Horizon
 * on-disk state without loading native libraries or touching Android UI classes so every rule can
 * also run on a host JVM test harness.
 */
final class GloryData {
    static final String BUNDLED_SHADER_ID = "StarlingVisuals.zip";
    static final String BUNDLED_SHADER_TITLE = "Starling Visuals";
    private static final String LAUNCH_ARGS_FILE = "glory-launch-args.txt";

    private GloryData() {}

    static final class AccountSummary {
        final String provider;
        final String uuid;
        final String key;
        final String name;
        final boolean selected;

        AccountSummary(String provider, String uuid, String key, String name, boolean selected) {
            this.provider = provider;
            this.uuid = uuid;
            this.key = key;
            this.name = name;
            this.selected = selected;
        }

        String providerLabel() {
            return "ely".equalsIgnoreCase(provider) ? "Ely.by" : "Microsoft";
        }
    }

    static final class ProfileSnapshot {
        final String activeName;
        final String activeProvider;
        final String activeKey;
        final File activeSkinFile;
        final List<AccountSummary> accounts;
        final List<String> offlineNames;
        final String offlineSelected;

        ProfileSnapshot(
                String activeName,
                String activeProvider,
                String activeKey,
                File activeSkinFile,
                List<AccountSummary> accounts,
                List<String> offlineNames,
                String offlineSelected) {
            this.activeName = activeName;
            this.activeProvider = activeProvider;
            this.activeKey = activeKey;
            this.activeSkinFile = activeSkinFile;
            this.accounts = accounts;
            this.offlineNames = offlineNames;
            this.offlineSelected = offlineSelected;
        }
    }

    static final class WorldSummary {
        final String folderName;
        final String levelName;
        final int gameType;
        final boolean hardcore;
        final long lastPlayedMs;
        final File iconFile;

        WorldSummary(
                String folderName,
                String levelName,
                int gameType,
                boolean hardcore,
                long lastPlayedMs,
                File iconFile) {
            this.folderName = folderName;
            this.levelName = levelName;
            this.gameType = gameType;
            this.hardcore = hardcore;
            this.lastPlayedMs = lastPlayedMs;
            this.iconFile = iconFile;
        }

        String modeLabel() {
            if (hardcore) {
                return "Hardcore";
            }
            switch (gameType) {
                case 1:
                    return "Creative";
                case 2:
                    return "Adventure";
                case 3:
                    return "Spectator";
                default:
                    return "Survival";
            }
        }
    }

    static final class ServerSummary {
        final String name;
        final String ip;
        final String iconBase64;

        ServerSummary(String name, String ip, String iconBase64) {
            this.name = name;
            this.ip = ip;
            this.iconBase64 = iconBase64;
        }
    }

    static final class ModSummary {
        final String id;
        final String title;
        final String version;
        final String description;
        final boolean isNative;
        final boolean enabled;

        ModSummary(
                String id,
                String title,
                String version,
                String description,
                boolean isNative,
                boolean enabled) {
            this.id = id;
            this.title = title;
            this.version = version;
            this.description = description;
            this.isNative = isNative;
            this.enabled = enabled;
        }
    }

    static final class ShaderPackSummary {
        final String id;
        final String title;
        final String subtitle;
        final boolean bundled;

        ShaderPackSummary(String id, String title, String subtitle, boolean bundled) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.bundled = bundled;
        }
    }

    static final class GraphicsConfig {
        final String pickedShaderPack;
        final String shadersProfile;
        final int resolutionScale;
        final String preset;
        final String overlay;
        final int maxFps;
        final int renderDistance;
        final int fov;
        final boolean autoJump;
        final boolean javaCreativeInventory;

        GraphicsConfig(
                String pickedShaderPack,
                String shadersProfile,
                int resolutionScale,
                String preset,
                String overlay,
                int maxFps,
                int renderDistance,
                int fov,
                boolean autoJump,
                boolean javaCreativeInventory) {
            this.pickedShaderPack = pickedShaderPack;
            this.shadersProfile = shadersProfile;
            this.resolutionScale = resolutionScale;
            this.preset = preset;
            this.overlay = overlay;
            this.maxFps = maxFps;
            this.renderDistance = renderDistance;
            this.fov = fov;
            this.autoJump = autoJump;
            this.javaCreativeInventory = javaCreativeInventory;
        }

        String shaderDisplayName() {
            if (pickedShaderPack == null || pickedShaderPack.isEmpty()) {
                return "Off (Vanilla)";
            }
            if (BUNDLED_SHADER_ID.equalsIgnoreCase(pickedShaderPack)
                    || "starling-visuals".equalsIgnoreCase(pickedShaderPack)) {
                return BUNDLED_SHADER_TITLE;
            }
            if (pickedShaderPack.endsWith(".zip") || pickedShaderPack.endsWith(".hshader")) {
                int dot = pickedShaderPack.lastIndexOf('.');
                return dot > 0 ? pickedShaderPack.substring(0, dot) : pickedShaderPack;
            }
            return pickedShaderPack;
        }
    }

    static final class TouchConfig {
        final String movement; // auto | analog | dpad
        final String anchor; // floating | fixed
        final String interact; // tap | crosshair
        final String sneak; // toggle | hold
        final int opacityPercent; // 20..100
        final boolean vibrate;
        final boolean gyro;
        final boolean threeFingers;

        TouchConfig(
                String movement,
                String anchor,
                String interact,
                String sneak,
                int opacityPercent,
                boolean vibrate,
                boolean gyro,
                boolean threeFingers) {
            this.movement = movement;
            this.anchor = anchor;
            this.interact = interact;
            this.sneak = sneak;
            this.opacityPercent = opacityPercent;
            this.vibrate = vibrate;
            this.gyro = gyro;
            this.threeFingers = threeFingers;
        }

        String movementLabel() {
            if ("dpad".equals(movement)) {
                return "D-Pad";
            }
            if ("analog".equals(movement)) {
                return "Stick";
            }
            return "Auto";
        }

        String interactLabel() {
            return "crosshair".equals(interact) ? "Crosshair" : "Tap";
        }
    }

    static final class DashboardSnapshot {
        final File privateDir;
        final File gameDir;
        final boolean usingSharedStorage;
        final boolean resourcesPrepared;
        final int pendingImportCount;
        final String nativeBuildStatus;
        final String sessionStatus;
        final int crashCount;
        final String latestLogTail;
        final ProfileSnapshot profile;
        final List<WorldSummary> worlds;
        final List<ServerSummary> servers;
        final List<ModSummary> mods;
        final List<ShaderPackSummary> shaderPacks;
        final GraphicsConfig graphics;
        final TouchConfig touch;

        DashboardSnapshot(
                File privateDir,
                File gameDir,
                boolean usingSharedStorage,
                boolean resourcesPrepared,
                int pendingImportCount,
                String nativeBuildStatus,
                String sessionStatus,
                int crashCount,
                String latestLogTail,
                ProfileSnapshot profile,
                List<WorldSummary> worlds,
                List<ServerSummary> servers,
                List<ModSummary> mods,
                List<ShaderPackSummary> shaderPacks,
                GraphicsConfig graphics,
                TouchConfig touch) {
            this.privateDir = privateDir;
            this.gameDir = gameDir;
            this.usingSharedStorage = usingSharedStorage;
            this.resourcesPrepared = resourcesPrepared;
            this.pendingImportCount = pendingImportCount;
            this.nativeBuildStatus = nativeBuildStatus;
            this.sessionStatus = sessionStatus;
            this.crashCount = crashCount;
            this.latestLogTail = latestLogTail;
            this.profile = profile;
            this.worlds = worlds;
            this.servers = servers;
            this.mods = mods;
            this.shaderPacks = shaderPacks;
            this.graphics = graphics;
            this.touch = touch;
        }

        int enabledModsCount() {
            int count = 0;
            for (ModSummary mod : mods) {
                if (mod.enabled) {
                    count++;
                }
            }
            return count;
        }

        ModSummary primaryMod() {
            for (ModSummary mod : mods) {
                if (mod.enabled) {
                    return mod;
                }
            }
            return mods.isEmpty() ? null : mods.get(0);
        }
    }

    static DashboardSnapshot load(File privateDir, File gameDir, boolean usingSharedStorage) {
        boolean prepared = new File(privateDir, "resources/horizon-resources.json").isFile();
        int pendingImports = countFiles(new File(privateDir, "import-inbox"));
        String nativeStatus = readNativeBuildStatus(privateDir);
        String sessionStatus = readSessionStatus(privateDir);
        int crashes = countCrashReports(new File(gameDir, "crash-reports"));
        String logTail = readLogTail(new File(gameDir, "logs/latest.log"), 12 * 1024);
        ProfileSnapshot profile = loadProfile(privateDir, gameDir);
        List<WorldSummary> worlds = loadWorlds(new File(gameDir, "saves"));
        List<ServerSummary> servers = loadServers(new File(gameDir, "servers.dat"));
        List<ModSummary> mods = loadMods(gameDir);
        List<ShaderPackSummary> shaderPacks = loadShaderPacks(new File(gameDir, "shaderpacks"));
        GraphicsConfig graphics = loadGraphicsConfig(gameDir);
        TouchConfig touch = loadTouchConfig(new File(gameDir, "touch-layout.json"));
        return new DashboardSnapshot(
                privateDir,
                gameDir,
                usingSharedStorage,
                prepared,
                pendingImports,
                nativeStatus,
                sessionStatus,
                crashes,
                logTail,
                profile,
                worlds,
                servers,
                mods,
                shaderPacks,
                graphics,
                touch);
    }

    // ---- Launch intent bridge (android.rs consume_launch_args) ----

    static void writeLaunchWorld(File privateDir, String worldFolderName) throws IOException {
        if (worldFolderName == null || worldFolderName.isEmpty()
                || worldFolderName.indexOf('/') >= 0 || worldFolderName.indexOf('\\') >= 0
                || ".".equals(worldFolderName) || "..".equals(worldFolderName)) {
            throw new IOException("Invalid world folder name");
        }
        writeAtomic(
                new File(privateDir, LAUNCH_ARGS_FILE),
                "--quickPlaySingleplayer\n" + worldFolderName + "\n");
    }

    static void writeLaunchServer(File privateDir, String address) throws IOException {
        if (address == null || address.trim().isEmpty()) {
            throw new IOException("Server address is empty");
        }
        writeAtomic(
                new File(privateDir, LAUNCH_ARGS_FILE),
                "--quickPlayMultiplayer\n" + address.trim() + "\n");
    }

    static void clearLaunchArgs(File privateDir) {
        File file = new File(privateDir, LAUNCH_ARGS_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    // ---- Accounts & Offline Profile ----

    static ProfileSnapshot loadProfile(File privateDir, File gameDir) {
        File accountsFile = new File(privateDir, "accounts/accounts.json");
        List<AccountSummary> accounts = new ArrayList<AccountSummary>();
        List<String> offlineNames = new ArrayList<String>();
        String selectedKey = null;
        String offlineSelected = null;

        String rawAccounts = readUtf8(accountsFile);
        if (rawAccounts != null && !rawAccounts.trim().isEmpty()) {
            try {
                JSONObject root = new JSONObject(rawAccounts);
                selectedKey = optNonEmpty(root, "selected");
                offlineSelected = optNonEmpty(root, "offline_selected");
                JSONArray list = root.optJSONArray("accounts");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        String provider = item.optString("provider", "microsoft");
                        String uuid = item.optString("uuid", "");
                        String name = item.optString("name", "");
                        if (uuid.isEmpty() || name.isEmpty()) {
                            continue;
                        }
                        String key = "ely".equalsIgnoreCase(provider) ? ("ely" + uuid) : uuid;
                        boolean isSelected = key.equals(selectedKey);
                        accounts.add(new AccountSummary(provider, uuid, key, name, isSelected));
                    }
                }
                JSONArray offlineArr = root.optJSONArray("offline");
                if (offlineArr != null) {
                    for (int i = 0; i < offlineArr.length(); i++) {
                        String n = offlineArr.optString(i, "").trim();
                        if (!n.isEmpty() && !offlineNames.contains(n)) {
                            offlineNames.add(n);
                        }
                    }
                }
            } catch (JSONException ignored) {
                // Fall back to horizon-player.txt below.
            }
        }

        Map<String, String> playerKv = readColonMap(new File(gameDir, "horizon-player.txt"));
        String playerTxtName = trimOrNull(playerKv.get("name"));
        if (playerTxtName != null && !offlineNames.contains(playerTxtName)) {
            offlineNames.add(0, playerTxtName);
        }

        AccountSummary activeAccount = null;
        for (AccountSummary acc : accounts) {
            if (acc.selected) {
                activeAccount = acc;
                break;
            }
        }

        String activeName;
        String activeProvider;
        String activeKey = null;
        if (activeAccount != null) {
            activeName = activeAccount.name;
            activeProvider = activeAccount.providerLabel();
            activeKey = activeAccount.key;
        } else {
            activeProvider = "Offline";
            if (offlineSelected != null && !offlineSelected.isEmpty()) {
                activeName = offlineSelected;
            } else if (playerTxtName != null) {
                activeName = playerTxtName;
            } else if (!offlineNames.isEmpty()) {
                activeName = offlineNames.get(0);
            } else {
                activeName = "Player";
            }
        }

        File skinFile = resolveSkinFile(gameDir, activeKey, playerKv);
        return new ProfileSnapshot(
                activeName,
                activeProvider,
                activeKey,
                skinFile,
                accounts,
                offlineNames,
                offlineSelected != null ? offlineSelected : playerTxtName);
    }

    private static File resolveSkinFile(
            File gameDir, String profileKey, Map<String, String> playerKv) {
        String chosenSkin = null;
        if (profileKey != null && !profileKey.isEmpty()) {
            String rawSkins = readUtf8(new File(gameDir, "horizon-skins.json"));
            if (rawSkins != null) {
                try {
                    JSONObject all = new JSONObject(rawSkins);
                    JSONObject entry = all.optJSONObject(profileKey);
                    if (entry != null) {
                        chosenSkin = optNonEmpty(entry, "skin");
                    }
                } catch (JSONException ignored) {
                    // Fallback below.
                }
            }
        }
        if (chosenSkin == null) {
            chosenSkin = trimOrNull(playerKv.get("skin"));
        }
        if (chosenSkin != null && isSafeSingleName(chosenSkin)) {
            File file = new File(new File(gameDir, "skins"), chosenSkin);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    static void selectAccount(File privateDir, String accountKey) throws IOException {
        File accountsFile = new File(privateDir, "accounts/accounts.json");
        JSONObject root = readJsonOrEmpty(accountsFile);
        try {
            if (!root.has("accounts")) {
                root.put("accounts", new JSONArray());
            }
            if (accountKey == null || accountKey.isEmpty()) {
                root.put("selected", JSONObject.NULL);
            } else {
                root.put("selected", accountKey);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (accountsFile.getParentFile() != null) {
            accountsFile.getParentFile().mkdirs();
        }
        writeAtomic(accountsFile, root.toString() + "\n");
    }

    static void selectOrAddOffline(File privateDir, File gameDir, String playerName)
            throws IOException {
        String clean = sanitizeOfflineName(playerName);
        if (clean.isEmpty()) {
            throw new IOException("Player name cannot be empty");
        }
        File accountsFile = new File(privateDir, "accounts/accounts.json");
        JSONObject root = readJsonOrEmpty(accountsFile);
        try {
            if (!root.has("accounts")) {
                root.put("accounts", new JSONArray());
            }
            root.put("selected", JSONObject.NULL);
            root.put("offline_selected", clean);
            JSONArray offline = root.optJSONArray("offline");
            if (offline == null) {
                offline = new JSONArray();
            }
            boolean exists = false;
            for (int i = 0; i < offline.length(); i++) {
                if (clean.equals(offline.optString(i))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                offline.put(clean);
            }
            root.put("offline", offline);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (accountsFile.getParentFile() != null) {
            accountsFile.getParentFile().mkdirs();
        }
        writeAtomic(accountsFile, root.toString() + "\n");

        File playerFile = new File(gameDir, "horizon-player.txt");
        if (playerFile.getParentFile() != null) {
            playerFile.getParentFile().mkdirs();
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("name", clean);
        values.put("chosen", "true");
        upsertColonKeys(playerFile, values);
    }

    static String sanitizeOfflineName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < 16; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_') {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---- Worlds (saves/*/level.dat) ----

    static List<WorldSummary> loadWorlds(File savesDir) {
        List<WorldSummary> result = new ArrayList<WorldSummary>();
        File[] folders = savesDir.listFiles();
        if (folders == null) {
            return result;
        }
        for (File dir : folders) {
            if (!dir.isDirectory()) {
                continue;
            }
            File levelDat = new File(dir, "level.dat");
            if (!levelDat.isFile()) {
                continue;
            }
            WorldSummary summary = readWorld(dir, levelDat);
            if (summary != null) {
                result.add(summary);
            }
        }
        Collections.sort(result, new Comparator<WorldSummary>() {
            @Override
            public int compare(WorldSummary a, WorldSummary b) {
                int byTime = Long.compare(b.lastPlayedMs, a.lastPlayedMs);
                return byTime != 0 ? byTime : a.levelName.compareToIgnoreCase(b.levelName);
            }
        });
        return result;
    }

    private static WorldSummary readWorld(File dir, File levelDat) {
        String folder = dir.getName();
        String levelName = folder;
        int gameType = 0;
        boolean hardcore = false;
        long lastPlayed = levelDat.lastModified();
        try (InputStream fis = new FileInputStream(levelDat);
                GZIPInputStream gis = new GZIPInputStream(new BufferedInputStream(fis));
                DataInputStream dis = new DataInputStream(gis)) {
            NbtCompound root = NbtReader.readNamedCompound(dis);
            if (root != null) {
                NbtCompound data = root.getCompound("Data");
                if (data == null) {
                    data = root;
                }
                String parsedName = data.getString("LevelName");
                if (parsedName != null && !parsedName.trim().isEmpty()) {
                    levelName = parsedName.trim();
                }
                gameType = data.getInt("GameType", 0);
                hardcore = data.getByte("hardcore", (byte) 0) != 0;
                long nbtTime = data.getLong("LastPlayed", 0L);
                if (nbtTime > 0L) {
                    lastPlayed = nbtTime;
                }
            }
        } catch (IOException ignored) {
            // Keep folder fallback metadata if level.dat is unreadable or partially written.
        }
        File icon = new File(dir, "icon.png");
        return new WorldSummary(
                folder,
                levelName,
                gameType,
                hardcore,
                lastPlayed,
                icon.isFile() ? icon : null);
    }

    // ---- Servers (servers.dat uncompressed NBT) ----

    static List<ServerSummary> loadServers(File serversDat) {
        List<ServerSummary> servers = new ArrayList<ServerSummary>();
        if (!serversDat.isFile()) {
            return servers;
        }
        try {
            byte[] bytes = Files.readAllBytes(serversDat.toPath());
            NbtCompound root = NbtReader.readNamedCompound(
                    new DataInputStream(new ByteArrayInputStream(bytes)));
            if (root == null) {
                return servers;
            }
            List<NbtCompound> entries = root.getCompoundList("servers");
            for (NbtCompound entry : entries) {
                String ip = entry.getString("ip");
                if (ip == null || ip.trim().isEmpty()) {
                    continue;
                }
                String name = entry.getString("name");
                if (name == null || name.trim().isEmpty()) {
                    name = ip.trim();
                }
                servers.add(new ServerSummary(name.trim(), ip.trim(), entry.getString("icon")));
            }
        } catch (IOException ignored) {
            // Empty server list when file is missing or corrupt.
        }
        return servers;
    }

    static void addServer(File serversDat, String name, String ip) throws IOException {
        String cleanIp = ip == null ? "" : ip.trim();
        if (cleanIp.isEmpty()) {
            throw new IOException("Server address is required");
        }
        String cleanName = (name == null || name.trim().isEmpty()) ? cleanIp : name.trim();
        List<NbtCompound> preserved = readRawServerCompounds(serversDat);
        NbtCompound added = new NbtCompound();
        added.putString("name", cleanName);
        added.putString("ip", cleanIp);
        preserved.add(added);
        writeServersDat(serversDat, preserved);
    }

    static void removeServer(File serversDat, int index) throws IOException {
        List<NbtCompound> preserved = readRawServerCompounds(serversDat);
        if (index >= 0 && index < preserved.size()) {
            preserved.remove(index);
            writeServersDat(serversDat, preserved);
        }
    }

    private static List<NbtCompound> readRawServerCompounds(File serversDat) {
        if (!serversDat.isFile()) {
            return new ArrayList<NbtCompound>();
        }
        try {
            byte[] bytes = Files.readAllBytes(serversDat.toPath());
            NbtCompound root = NbtReader.readNamedCompound(
                    new DataInputStream(new ByteArrayInputStream(bytes)));
            if (root != null) {
                return new ArrayList<NbtCompound>(root.getCompoundList("servers"));
            }
        } catch (IOException ignored) {
            // Treat unreadable file as empty.
        }
        return new ArrayList<NbtCompound>();
    }

    private static void writeServersDat(File serversDat, List<NbtCompound> entries)
            throws IOException {
        NbtCompound root = new NbtCompound();
        root.putCompoundList("servers", entries);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        NbtWriter.writeNamedCompound(dos, "", root);
        dos.flush();
        if (serversDat.getParentFile() != null) {
            serversDat.getParentFile().mkdirs();
        }
        writeBytesAtomic(serversDat, baos.toByteArray());
    }

    // ---- Mods (horizon-mods/, horizon-native-mods/, horizon-mods.txt) ----

    static List<ModSummary> loadMods(File gameDir) {
        Map<String, String> modTxt = readColonMap(new File(gameDir, "horizon-mods.txt"));
        Set<String> enabledSet = new LinkedHashSet<String>();
        Set<String> disabledSet = new LinkedHashSet<String>();
        for (Map.Entry<String, String> entry : modTxt.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("mod.")) {
                String id = key.substring(4);
                String val = entry.getValue().trim();
                if ("true".equals(val)) {
                    enabledSet.add(id);
                } else if ("false".equals(val)) {
                    disabledSet.add(id);
                }
            }
        }

        List<ModSummary> mods = new ArrayList<ModSummary>();
        scanModFolder(new File(gameDir, "horizon-native-mods"), true, enabledSet, disabledSet, mods);
        scanModFolder(new File(gameDir, "horizon-mods"), false, enabledSet, disabledSet, mods);
        Collections.sort(mods, new Comparator<ModSummary>() {
            @Override
            public int compare(ModSummary a, ModSummary b) {
                if (a.isNative != b.isNative) {
                    return a.isNative ? -1 : 1;
                }
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return mods;
    }

    private static void scanModFolder(
            File dir,
            boolean isNative,
            Set<String> enabledSet,
            Set<String> disabledSet,
            List<ModSummary> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.getName().startsWith(".")) {
                continue;
            }
            ModSummary meta = null;
            if (entry.isDirectory()) {
                meta = readModMetaFromDir(entry, isNative);
            } else {
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (isNative ? lower.endsWith(".hnmod") : (lower.endsWith(".hmod") || lower.endsWith(".zip"))) {
                    meta = readModMetaFromZip(entry, isNative);
                }
            }
            if (meta == null) {
                continue;
            }
            boolean on = isNative
                    ? !disabledSet.contains(meta.id)
                    : enabledSet.contains(meta.id);
            out.add(new ModSummary(
                    meta.id, meta.title, meta.version, meta.description, isNative, on));
        }
    }

    private static ModSummary readModMetaFromDir(File dir, boolean isNative) {
        if (isNative) {
            File jsonFile = new File(dir, "horizon-native.json");
            if (jsonFile.isFile()) {
                return parseNativeModJson(readUtf8(jsonFile), dir.getName());
            }
        }
        File tomlFile = new File(dir, "horizon-mod.toml");
        if (tomlFile.isFile()) {
            return parseModToml(readUtf8(tomlFile), dir.getName(), isNative);
        }
        return null;
    }

    private static ModSummary readModMetaFromZip(File zipFile, boolean isNative) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            if (isNative) {
                ZipEntry jsonEntry = findEntry(zip, "horizon-native.json");
                if (jsonEntry != null) {
                    return parseNativeModJson(readZipEntry(zip, jsonEntry), stripExt(zipFile.getName()));
                }
            }
            ZipEntry tomlEntry = findEntry(zip, "horizon-mod.toml");
            if (tomlEntry != null) {
                return parseModToml(
                        readZipEntry(zip, tomlEntry), stripExt(zipFile.getName()), isNative);
            }
        } catch (IOException ignored) {
            // Skip broken archive.
        }
        return null;
    }

    private static ModSummary parseNativeModJson(String raw, String fallbackId) {
        if (raw == null) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(raw);
            String id = obj.optString("id", fallbackId);
            String title = obj.optString("name", obj.optString("title", id));
            String version = obj.optString("version", "");
            String desc = obj.optString("description", "Native ARM64 mixin mod");
            return new ModSummary(id, title, version, desc, true, true);
        } catch (JSONException e) {
            return null;
        }
    }

    private static ModSummary parseModToml(String raw, String fallbackId, boolean isNative) {
        if (raw == null) {
            return null;
        }
        Map<String, String> kv = parseSimpleToml(raw);
        String id = nonEmptyOr(kv.get("id"), fallbackId);
        String title = nonEmptyOr(kv.get("name"), nonEmptyOr(kv.get("title"), id));
        String version = nonEmptyOr(kv.get("version"), "");
        String desc = nonEmptyOr(kv.get("description"), isNative ? "Native mod" : "Content mod");
        return new ModSummary(id, title, version, desc, isNative, false);
    }

    static void setModEnabled(File gameDir, String modId, boolean isNative, boolean enabled)
            throws IOException {
        if (modId == null || modId.trim().isEmpty()) {
            return;
        }
        File file = new File(gameDir, "horizon-mods.txt");
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        String key = "mod." + modId.trim();
        Map<String, String> update = new LinkedHashMap<String, String>();
        if (isNative) {
            update.put(key, enabled ? "true" : "false");
        } else if (enabled) {
            update.put(key, "true");
        } else {
            removeColonKey(file, key);
            return;
        }
        upsertColonKeys(file, update);
    }

    // ---- Shaders & Graphics ----

    static List<ShaderPackSummary> loadShaderPacks(File shaderpacksDir) {
        List<ShaderPackSummary> packs = new ArrayList<ShaderPackSummary>();
        boolean foundBundled = false;
        File[] entries = shaderpacksDir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                String name = entry.getName();
                if (name.startsWith(".")) {
                    continue;
                }
                if (BUNDLED_SHADER_ID.equalsIgnoreCase(name)
                        || "starling-visuals".equalsIgnoreCase(name)) {
                    foundBundled = true;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (entry.isDirectory()) {
                    if (new File(entry, "horizon-shaders.toml").isFile()
                            || new File(entry, "shaders").isDirectory()) {
                        String title = stripExt(name);
                        if ("starling-visuals".equalsIgnoreCase(name)) {
                            title = BUNDLED_SHADER_TITLE;
                        }
                        packs.add(new ShaderPackSummary(
                                name, title, "Folder shader pack", false));
                    }
                } else if (lower.endsWith(".zip") || lower.endsWith(".hshader")) {
                    String title = BUNDLED_SHADER_ID.equalsIgnoreCase(name)
                            ? BUNDLED_SHADER_TITLE
                            : stripExt(name);
                    String sub = BUNDLED_SHADER_ID.equalsIgnoreCase(name)
                            ? "Bundled mobile GLSL pack"
                            : (lower.endsWith(".hshader") ? "Horizon shader pack" : "Iris / GLSL pack");
                    packs.add(new ShaderPackSummary(
                            name, title, sub, BUNDLED_SHADER_ID.equalsIgnoreCase(name)));
                }
            }
        }
        if (!foundBundled) {
            packs.add(0, new ShaderPackSummary(
                    BUNDLED_SHADER_ID,
                    BUNDLED_SHADER_TITLE,
                    "Bundled mobile GLSL pack",
                    true));
        }
        Collections.sort(packs, new Comparator<ShaderPackSummary>() {
            @Override
            public int compare(ShaderPackSummary a, ShaderPackSummary b) {
                if (a.bundled != b.bundled) {
                    return a.bundled ? -1 : 1;
                }
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return packs;
    }

    static GraphicsConfig loadGraphicsConfig(File gameDir) {
        Map<String, String> gfx = readColonMap(new File(gameDir, "horizon-graphics.txt"));
        Map<String, String> opt = readColonMap(new File(gameDir, "options.txt"));

        String pack = gfx.containsKey("pickedShaderPack")
                ? gfx.get("pickedShaderPack").trim()
                : "";
        if (pack.isEmpty() && gfx.containsKey("shaders")) {
            String legacy = gfx.get("shaders").trim();
            if (!"off".equalsIgnoreCase(legacy)) {
                pack = legacy;
            }
        }
        String profile = gfx.containsKey("shadersProfile")
                ? gfx.get("shadersProfile").trim()
                : "";
        int scale = clampInt(parseIntOr(gfx.get("resolutionScale"), 100), 25, 200);
        String preset = nonEmptyOr(gfx.get("preset"), "balanced");
        String overlay = nonEmptyOr(gfx.get("overlay"), "off");

        int maxFps = clampInt(parseIntOr(opt.get("maxFps"), 120), 10, 260);
        int renderDistance = clampInt(parseIntOr(opt.get("renderDistance"), 12), 2, 64);
        int fov = clampInt(parseIntOr(opt.get("fov"), 70), 30, 110);
        boolean autoJump = "true".equalsIgnoreCase(trimOrNull(opt.get("autoJump")));
        boolean javaCreative =
                "true".equalsIgnoreCase(trimOrNull(opt.get("javaCreativeInventory")));

        return new GraphicsConfig(
                pack,
                profile,
                scale,
                preset,
                overlay,
                maxFps,
                renderDistance,
                fov,
                autoJump,
                javaCreative);
    }

    static void updateGraphicsKey(File gameDir, String key, String value) throws IOException {
        File file = new File(gameDir, "horizon-graphics.txt");
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        Map<String, String> update = new LinkedHashMap<String, String>();
        update.put(key, value == null ? "" : value);
        upsertColonKeys(file, update);
    }

    static void updateOptionKey(File gameDir, String key, String value) throws IOException {
        File file = new File(gameDir, "options.txt");
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        Map<String, String> update = new LinkedHashMap<String, String>();
        update.put(key, value == null ? "" : value);
        upsertColonKeys(file, update);
    }

    // ---- Touch Layout (touch-layout.json) ----

    static TouchConfig loadTouchConfig(File layoutFile) {
        String raw = readUtf8(layoutFile);
        if (raw == null || raw.trim().isEmpty()) {
            return new TouchConfig("auto", "floating", "tap", "toggle", 100, true, false, false);
        }
        try {
            JSONObject obj = new JSONObject(raw);
            String movement = obj.optString("movement", "auto");
            if (!"dpad".equals(movement) && !"analog".equals(movement)) {
                movement = "auto";
            }
            String anchor = "fixed".equals(obj.optString("anchor", "floating")) ? "fixed" : "floating";
            String interact = "crosshair".equals(obj.optString("interact", "tap")) ? "crosshair" : "tap";
            String sneak = "hold".equals(obj.optString("sneak", "toggle")) ? "hold" : "toggle";
            double opacityVal = obj.optDouble("opacity", 1.0);
            int opacityPct = clampInt((int) Math.round(opacityVal * 100.0), 20, 100);
            boolean vibrate = obj.optBoolean("vibrate", true);
            boolean gyro = obj.optBoolean("gyro", false);
            boolean threeFingers = obj.optBoolean("three_fingers", false);
            return new TouchConfig(
                    movement, anchor, interact, sneak, opacityPct, vibrate, gyro, threeFingers);
        } catch (JSONException e) {
            return new TouchConfig("auto", "floating", "tap", "toggle", 100, true, false, false);
        }
    }

    static void saveTouchConfig(File gameDir, TouchConfig cfg) throws IOException {
        File file = new File(gameDir, "touch-layout.json");
        JSONObject root = readJsonOrEmpty(file);
        try {
            if (!root.has("version")) {
                root.put("version", 2);
            }
            root.put("movement", cfg.movement);
            root.put("anchor", cfg.anchor);
            root.put("interact", cfg.interact);
            root.put("sneak", cfg.sneak);
            root.put("opacity", Math.max(0.2, Math.min(1.0, cfg.opacityPercent / 100.0)));
            root.put("vibrate", cfg.vibrate);
            root.put("gyro", cfg.gyro);
            root.put("three_fingers", cfg.threeFingers);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        writeAtomic(file, root.toString() + "\n");
    }

    static void resetTouchConfig(File gameDir) {
        File file = new File(gameDir, "touch-layout.json");
        if (file.exists()) {
            file.delete();
        }
    }

    // ---- Diagnostics helpers ----

    private static String readNativeBuildStatus(File privateDir) {
        File nativeDir = new File(privateDir, "native");
        String current = trimOrNull(readUtf8(new File(nativeDir, "current")));
        if (current == null || current.isEmpty()) {
            return "Stock APK build";
        }
        File lib = new File(new File(nativeDir, current), NativeMods.LIBRARY);
        if (lib.isFile()) {
            return "Active (" + current.substring(0, Math.min(8, current.length())) + ")";
        }
        return "Pending rebuild";
    }

    private static String readSessionStatus(File privateDir) {
        File session = new File(privateDir, "performance/current-session.json");
        String raw = readUtf8(session);
        if (raw == null || raw.trim().isEmpty()) {
            return "No previous session recorded";
        }
        try {
            JSONObject obj = new JSONObject(raw);
            boolean clean = obj.optBoolean("clean_exit", false);
            String phase = obj.optString("phase", "unknown");
            if (clean) {
                return "Last session exited cleanly";
            }
            return "Last phase: " + phase;
        } catch (JSONException e) {
            return "Session file unreadable";
        }
    }

    private static int countCrashReports(File crashDir) {
        File[] files = crashDir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File f : files) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                count++;
            }
        }
        return count;
    }

    private static int countFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File f : files) {
            if (f.isFile() && !f.getName().endsWith(".part")) {
                count++;
            }
        }
        return count;
    }

    static String readLogTail(File logFile, int maxBytes) {
        if (!logFile.isFile()) {
            return "No game log yet. Launch the game once to populate logs/latest.log.";
        }
        try (FileInputStream fis = new FileInputStream(logFile)) {
            long len = logFile.length();
            long skip = Math.max(0L, len - maxBytes);
            if (skip > 0) {
                long remaining = skip;
                while (remaining > 0) {
                    long skipped = fis.skip(remaining);
                    if (skipped <= 0) {
                        break;
                    }
                    remaining -= skipped;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (skip > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0 && nl + 1 < text.length()) {
                    text = text.substring(nl + 1);
                }
            }
            return text.isEmpty() ? "(log is empty)" : text;
        } catch (IOException e) {
            return "Unable to read latest.log: " + e.getMessage();
        }
    }

    // ---- Low-level file & text helpers ----

    static Map<String, String> readColonMap(File file) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        String raw = readUtf8(file);
        if (raw == null) {
            return map;
        }
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            map.put(key, val);
        }
        return map;
    }

    static void upsertColonKeys(File file, Map<String, String> updates) throws IOException {
        List<String> lines = new ArrayList<String>();
        String raw = readUtf8(file);
        if (raw != null && !raw.isEmpty()) {
            String[] existing = raw.split("\\r?\\n", -1);
            for (int i = 0; i < existing.length; i++) {
                if (i == existing.length - 1 && existing[i].isEmpty()) {
                    continue;
                }
                lines.add(existing[i]);
            }
        }
        Set<String> applied = new LinkedHashSet<String>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int idx = line.indexOf(':');
            if (idx > 0) {
                String k = line.substring(0, idx).trim();
                if (updates.containsKey(k)) {
                    lines.set(i, k + ":" + updates.get(k));
                    applied.add(k);
                }
            }
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            if (!applied.contains(entry.getKey())) {
                lines.add(entry.getKey() + ":" + entry.getValue());
            }
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        writeAtomic(file, out.toString());
    }

    static void removeColonKey(File file, String keyToRemove) throws IOException {
        String raw = readUtf8(file);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String[] existing = raw.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < existing.length; i++) {
            String line = existing[i];
            if (i == existing.length - 1 && line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equals(keyToRemove)) {
                continue;
            }
            out.append(line).append('\n');
        }
        writeAtomic(file, out.toString());
    }

    private static Map<String, String> parseSimpleToml(String raw) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = t.substring(0, eq).trim();
            String v = t.substring(eq + 1).trim();
            if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                    || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.substring(1, v.length() - 1);
            }
            map.put(k, v);
        }
        return map;
    }

    private static ZipEntry findEntry(ZipFile zip, String targetName) {
        ZipEntry exact = zip.getEntry(targetName);
        if (exact != null) {
            return exact;
        }
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (!candidate.isDirectory() && candidate.getName().endsWith("/" + targetName)) {
                return candidate;
            }
        }
        return null;
    }

    private static String readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
                if (baos.size() > 512 * 1024) {
                    break;
                }
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject readJsonOrEmpty(File file) {
        String raw = readUtf8(file);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                return new JSONObject(raw);
            } catch (JSONException ignored) {
                // Replace malformed file with fresh JSON.
            }
        }
        return new JSONObject();
    }

    static String readUtf8(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static void writeAtomic(File file, String content) throws IOException {
        writeBytesAtomic(file, content.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBytesAtomic(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File part = new File(parent, file.getName() + ".part");
        try (FileOutputStream fos = new FileOutputStream(part)) {
            fos.write(bytes);
            fos.getFD().sync();
        }
        Files.move(
                part.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isSafeSingleName(String name) {
        return !name.isEmpty()
                && !".".equals(name)
                && !"..".equals(name)
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String optNonEmpty(JSONObject obj, String key) {
        if (obj.isNull(key)) {
            return null;
        }
        String v = obj.optString(key, "");
        return v.trim().isEmpty() ? null : v.trim();
    }

    private static String trimOrNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nonEmptyOr(String v, String fallback) {
        String t = trimOrNull(v);
        return t == null ? fallback : t;
    }

    private static int parseIntOr(String v, int fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- Minimal NBT Reader / Writer (saves/*/level.dat + servers.dat) ----

    static final class NbtCompound {
        final Map<String, Object> fields = new LinkedHashMap<String, Object>();

        void putString(String key, String value) {
            fields.put(key, value);
        }

        void putCompoundList(String key, List<NbtCompound> value) {
            fields.put(key, new NbtList(10, new ArrayList<Object>(value)));
        }

        String getString(String key) {
            Object v = fields.get(key);
            return v instanceof String ? (String) v : null;
        }

        int getInt(String key, int fallback) {
            Object v = fields.get(key);
            return v instanceof Number ? ((Number) v).intValue() : fallback;
        }

        byte getByte(String key, byte fallback) {
            Object v = fields.get(key);
            return v instanceof Number ? ((Number) v).byteValue() : fallback;
        }

        long getLong(String key, long fallback) {
            Object v = fields.get(key);
            return v instanceof Number ? ((Number) v).longValue() : fallback;
        }

        NbtCompound getCompound(String key) {
            Object v = fields.get(key);
            return v instanceof NbtCompound ? (NbtCompound) v : null;
        }

        List<NbtCompound> getCompoundList(String key) {
            Object v = fields.get(key);
            if (!(v instanceof NbtList)) {
                return Collections.emptyList();
            }
            NbtList list = (NbtList) v;
            List<NbtCompound> out = new ArrayList<NbtCompound>();
            for (Object item : list.items) {
                if (item instanceof NbtCompound) {
                    out.add((NbtCompound) item);
                }
            }
            return out;
        }
    }

    private static final class NbtList {
        final int elementType;
        final List<Object> items;

        NbtList(int elementType, List<Object> items) {
            this.elementType = elementType;
            this.items = items;
        }
    }

    private static final class NbtReader {
        private static final int MAX_DEPTH = 64;
        private static final int MAX_LIST = 65536;

        static NbtCompound readNamedCompound(DataInputStream in) throws IOException {
            int type;
            try {
                type = in.readUnsignedByte();
            } catch (EOFException e) {
                return null;
            }
            if (type != 10) {
                return null;
            }
            readString(in);
            return readCompound(in, 0);
        }

        private static NbtCompound readCompound(DataInputStream in, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("NBT too deep");
            }
            NbtCompound compound = new NbtCompound();
            while (true) {
                int type = in.readUnsignedByte();
                if (type == 0) {
                    return compound;
                }
                String name = readString(in);
                Object payload = readPayload(in, type, depth + 1);
                compound.fields.put(name, payload);
            }
        }

        private static Object readPayload(DataInputStream in, int type, int depth)
                throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("NBT too deep");
            }
            switch (type) {
                case 1:
                    return in.readByte();
                case 2:
                    return in.readShort();
                case 3:
                    return in.readInt();
                case 4:
                    return in.readLong();
                case 5:
                    return in.readFloat();
                case 6:
                    return in.readDouble();
                case 7: {
                    int len = in.readInt();
                    if (len < 0 || len > 16 * 1024 * 1024) {
                        throw new IOException("Invalid byte array");
                    }
                    byte[] arr = new byte[len];
                    in.readFully(arr);
                    return arr;
                }
                case 8:
                    return readString(in);
                case 9: {
                    int elemType = in.readUnsignedByte();
                    int len = in.readInt();
                    if (len < 0 || len > MAX_LIST) {
                        throw new IOException("Invalid NBT list length");
                    }
                    List<Object> items = new ArrayList<Object>(Math.min(len, 64));
                    for (int i = 0; i < len; i++) {
                        items.add(readPayload(in, elemType, depth + 1));
                    }
                    return new NbtList(elemType, items);
                }
                case 10:
                    return readCompound(in, depth + 1);
                case 11: {
                    int len = in.readInt();
                    if (len < 0 || len > 4 * 1024 * 1024) {
                        throw new IOException("Invalid int array");
                    }
                    int[] arr = new int[len];
                    for (int i = 0; i < len; i++) {
                        arr[i] = in.readInt();
                    }
                    return arr;
                }
                case 12: {
                    int len = in.readInt();
                    if (len < 0 || len > 4 * 1024 * 1024) {
                        throw new IOException("Invalid long array");
                    }
                    long[] arr = new long[len];
                    for (int i = 0; i < len; i++) {
                        arr[i] = in.readLong();
                    }
                    return arr;
                }
                default:
                    throw new IOException("Unknown NBT tag type: " + type);
            }
        }

        private static String readString(DataInputStream in) throws IOException {
            int len = in.readUnsignedShort();
            byte[] utf = new byte[len];
            in.readFully(utf);
            return new String(utf, StandardCharsets.UTF_8);
        }
    }

    private static final class NbtWriter {
        static void writeNamedCompound(DataOutputStream out, String name, NbtCompound compound)
                throws IOException {
            out.writeByte(10);
            writeString(out, name);
            writeCompound(out, compound);
        }

        private static void writeCompound(DataOutputStream out, NbtCompound compound)
                throws IOException {
            for (Map.Entry<String, Object> entry : compound.fields.entrySet()) {
                Object value = entry.getValue();
                int type = tagTypeOf(value);
                if (type == 0) {
                    continue;
                }
                out.writeByte(type);
                writeString(out, entry.getKey());
                writePayload(out, type, value);
            }
            out.writeByte(0);
        }

        private static int tagTypeOf(Object value) {
            if (value instanceof Byte) {
                return 1;
            }
            if (value instanceof Short) {
                return 2;
            }
            if (value instanceof Integer) {
                return 3;
            }
            if (value instanceof Long) {
                return 4;
            }
            if (value instanceof Float) {
                return 5;
            }
            if (value instanceof Double) {
                return 6;
            }
            if (value instanceof byte[]) {
                return 7;
            }
            if (value instanceof String) {
                return 8;
            }
            if (value instanceof NbtList) {
                return 9;
            }
            if (value instanceof NbtCompound) {
                return 10;
            }
            if (value instanceof int[]) {
                return 11;
            }
            if (value instanceof long[]) {
                return 12;
            }
            return 0;
        }

        private static void writePayload(DataOutputStream out, int type, Object value)
                throws IOException {
            switch (type) {
                case 1:
                    out.writeByte((Byte) value);
                    break;
                case 2:
                    out.writeShort((Short) value);
                    break;
                case 3:
                    out.writeInt((Integer) value);
                    break;
                case 4:
                    out.writeLong((Long) value);
                    break;
                case 5:
                    out.writeFloat((Float) value);
                    break;
                case 6:
                    out.writeDouble((Double) value);
                    break;
                case 7: {
                    byte[] arr = (byte[]) value;
                    out.writeInt(arr.length);
                    out.write(arr);
                    break;
                }
                case 8:
                    writeString(out, (String) value);
                    break;
                case 9: {
                    NbtList list = (NbtList) value;
                    out.writeByte(list.elementType);
                    out.writeInt(list.items.size());
                    for (Object item : list.items) {
                        writePayload(out, list.elementType, item);
                    }
                    break;
                }
                case 10:
                    writeCompound(out, (NbtCompound) value);
                    break;
                case 11: {
                    int[] arr = (int[]) value;
                    out.writeInt(arr.length);
                    for (int n : arr) {
                        out.writeInt(n);
                    }
                    break;
                }
                case 12: {
                    long[] arr = (long[]) value;
                    out.writeInt(arr.length);
                    for (long n : arr) {
                        out.writeLong(n);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        private static void writeString(DataOutputStream out, String text) throws IOException {
            byte[] utf = text.getBytes(StandardCharsets.UTF_8);
            out.writeShort(utf.length);
            out.write(utf);
        }
    }
}
