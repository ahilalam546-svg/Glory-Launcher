"""Checks for the Glory Launcher Android dashboard, manifest, resources, and launch handoff."""

import re
import shutil
import struct
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from tools import check_public_tree

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "packaging" / "android"
JAVA_DIR = ANDROID / "java" / "io" / "github" / "juanluci" / "starling"
NS = "{http://schemas.android.com/apk/res/android}"


class GloryManifestAndResources(unittest.TestCase):
    def test_glory_branding_and_supplied_logo_are_used(self):
        build = (ANDROID / "build_apk.py").read_text(encoding="utf-8")
        self.assertIn('LABEL = "Glory Launcher"', build)
        self.assertIn('VARIANTS = {"dev": (DEV_SUFFIX, ""), "preview": (".preview", "")}', build)

        manifest = ET.parse(ANDROID / "AndroidManifest.xml").getroot()
        app = manifest.find("application")
        self.assertEqual(app.get(NS + "label"), "@LABEL@")
        self.assertEqual(app.get(NS + "icon"), "@mipmap/ic_launcher")

        strings = ET.parse(ANDROID / "res/values/glory_strings.xml").getroot()
        values = {item.get("name"): item.text for item in strings.findall("string")}
        self.assertEqual(values["glory_launch_game"], "PLAY")
        self.assertEqual(values["glory_complete_setup"], "PLAY")

        for density, expected in (("mdpi", 48), ("xxhdpi", 128), ("xxxhdpi", 256)):
            icon = ANDROID / f"res/mipmap-{density}/ic_launcher.png"
            data = icon.read_bytes()
            self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(struct.unpack(">II", data[16:24]), (expected, expected))

    def test_launcher_activity_runs_as_persistent_landscape_dashboard(self):
        manifest = ET.parse(ANDROID / "AndroidManifest.xml").getroot()
        app = manifest.find("application")
        launcher = next(
            a for a in app.findall("activity")
            if a.get(NS + "name") == "io.github.juanluci.starling.LauncherActivity"
        )
        self.assertEqual(launcher.get(NS + "process"), ":launcher")
        self.assertEqual(launcher.get(NS + "launchMode"), "singleTask")
        self.assertEqual(launcher.get(NS + "screenOrientation"), "sensorLandscape")
        self.assertEqual(launcher.get(NS + "theme"), "@style/Glory.Launcher")
        self.assertIsNone(launcher.get(NS + "noHistory"))
        self.assertIsNone(launcher.get(NS + "excludeFromRecents"))

        alias = next(
            a for a in app.findall("activity-alias")
            if a.get(NS + "name") == "io.github.juanluci.starling.StarlingActivity"
        )
        self.assertEqual(
            alias.get(NS + "targetActivity"),
            "io.github.juanluci.starling.LauncherActivity",
        )

    def test_xml_resources_parse_and_define_glory_theme(self):
        for rel in (
            "res/values/glory_colors.xml",
            "res/values/glory_strings.xml",
            "res/values/themes.xml",
            "res/values-v31/themes.xml",
        ):
            root = ET.parse(ANDROID / rel).getroot()
            self.assertEqual(root.tag, "resources", rel)

        themes = ET.parse(ANDROID / "res/values/themes.xml").getroot()
        styles = {s.get("name") for s in themes.findall("style")}
        self.assertIn("Glory.Launcher", styles)
        self.assertIn("Starling.Game", styles)

        themes_v31 = ET.parse(ANDROID / "res/values-v31/themes.xml").getroot()
        styles_v31 = {s.get("name") for s in themes_v31.findall("style")}
        self.assertIn("Glory.Launcher", styles_v31)

    def test_java_sources_are_public_tree_allowed_and_java8_clean(self):
        for name in ("LauncherActivity.java", "GloryData.java", "GloryArtwork.java"):
            rel = f"packaging/android/java/io/github/juanluci/starling/{name}"
            self.assertFalse(check_public_tree.prohibited(rel), rel)
            src = (JAVA_DIR / name).read_text(encoding="utf-8")
            self.assertNotIn("androidx.", src, name)
            self.assertNotIn("com.google.android.material", src, name)
            for forbidden in ("Files.readString", "Files.writeString", "readAllBytes()", "List.of(", "Map.of("):
                self.assertNotIn(forbidden, src, f"{name} must stay Java 8 compatible: {forbidden}")

    def test_rust_android_consumes_one_shot_launch_args_without_polluting_recovery(self):
        rust = (ROOT / "crates/horizon-client/src/android.rs").read_text(encoding="utf-8")
        self.assertIn("fn consume_launch_args", rust)
        self.assertIn("glory-launch-args.txt", rust)
        self.assertIn("consume_launch_args(&private, &mut args);", rust)
        # Ensure recovery_report still reads command_line without deleting launch args.
        recovery = re.search(r"pub fn recovery_report\(.*?\n\}", rust, re.S).group(0)
        self.assertIn("command_line(private)", recovery)
        self.assertNotIn("consume_launch_args", recovery)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "no JDK")
class GloryDataBehavior(unittest.TestCase):
    def test_nbt_configs_and_launch_args_with_host_jdk(self):
        with tempfile.TemporaryDirectory() as temp:
            tmp = Path(temp)
            src = tmp / "src"
            pkg = src / "io/github/juanluci/starling"
            org_json = src / "org/json"
            classes = tmp / "classes"
            work = tmp / "work"
            pkg.mkdir(parents=True)
            org_json.mkdir(parents=True)
            classes.mkdir()
            work.mkdir()

            shutil.copy2(JAVA_DIR / "GloryData.java", pkg / "GloryData.java")
            shutil.copy2(JAVA_DIR / "NativeMods.java", pkg / "NativeMods.java")
            # Minimal host-JDK stub for android.content.Context / PackageManager used by NativeMods.
            android_content = src / "android/content/pm"
            android_content.mkdir(parents=True)
            (src / "android/content/Context.java").write_text(
                "package android.content;\nimport java.io.File;\n"
                "public class Context { public File getFilesDir() { return null; } "
                "public String getPackageName() { return \"\"; } "
                "public android.content.pm.PackageManager getPackageManager() { return null; } }\n",
                encoding="utf-8",
            )
            (android_content / "PackageManager.java").write_text(
                "package android.content.pm;\n"
                "public class PackageManager { public static class NameNotFoundException extends Exception {} "
                "public PackageInfo getPackageInfo(String p, int f) throws NameNotFoundException { return new PackageInfo(); } }\n",
                encoding="utf-8",
            )
            (android_content / "PackageInfo.java").write_text(
                "package android.content.pm;\npublic class PackageInfo { public long lastUpdateTime; }\n",
                encoding="utf-8",
            )
            # Minimal org.json shims when running outside Android's android.jar.
            (org_json / "JSONException.java").write_text(
                "package org.json;\npublic class JSONException extends Exception { public JSONException(String m) { super(m); } }\n",
                encoding="utf-8",
            )
            (org_json / "JSONArray.java").write_text(
                "package org.json;\nimport java.util.*;\npublic class JSONArray {\n"
                "  private final List<Object> items = new ArrayList<Object>();\n"
                "  public int length() { return items.size(); }\n"
                "  public String optString(int i) { return optString(i, \"\"); }\n"
                "  public String optString(int i, String d) { return i < items.size() && items.get(i) != null ? items.get(i).toString() : d; }\n"
                "  public JSONObject optJSONObject(int i) { return i < items.size() && items.get(i) instanceof JSONObject ? (JSONObject) items.get(i) : null; }\n"
                "  public JSONArray put(Object v) { items.add(v); return this; }\n"
                "}\n",
                encoding="utf-8",
            )
            (org_json / "JSONObject.java").write_text(
                "package org.json;\nimport java.util.*;\npublic class JSONObject {\n"
                "  public static final Object NULL = new Object();\n"
                "  private final Map<String, Object> m = new LinkedHashMap<String, Object>();\n"
                "  public JSONObject() {}\n"
                "  public JSONObject(String s) throws JSONException {}\n"
                "  public boolean has(String k) { return m.containsKey(k); }\n"
                "  public boolean isNull(String k) { return m.get(k) == null || m.get(k) == NULL; }\n"
                "  public String optString(String k, String d) { Object v = m.get(k); return v == null || v == NULL ? d : v.toString(); }\n"
                "  public boolean optBoolean(String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? (Boolean) v : d; }\n"
                "  public double optDouble(String k, double d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).doubleValue() : d; }\n"
                "  public JSONArray optJSONArray(String k) { Object v = m.get(k); return v instanceof JSONArray ? (JSONArray) v : null; }\n"
                "  public JSONObject optJSONObject(String k) { Object v = m.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }\n"
                "  public JSONObject put(String k, Object v) throws JSONException { m.put(k, v); return this; }\n"
                "  public JSONObject put(String k, boolean v) throws JSONException { m.put(k, v); return this; }\n"
                "  public JSONObject put(String k, int v) throws JSONException { m.put(k, v); return this; }\n"
                "  public JSONObject put(String k, double v) throws JSONException { m.put(k, v); return this; }\n"
                "  public String toString() { return \"{}\"; }\n"
                "}\n",
                encoding="utf-8",
            )

            checks = pkg / "GloryDataChecks.java"
            checks.write_text(
                "package io.github.juanluci.starling;\n"
                "import java.io.File;\n"
                "import java.util.List;\n"
                "public final class GloryDataChecks {\n"
                "  public static void main(String[] args) throws Exception {\n"
                "    File root = new File(args[0]);\n"
                "    File priv = new File(root, \"private\");\n"
                "    File game = new File(root, \"game\");\n"
                "    priv.mkdirs(); game.mkdirs();\n"
                "    GloryData.writeLaunchWorld(priv, \"MyWorld\");\n"
                "    String launched = GloryData.readUtf8(new File(priv, \"glory-launch-args.txt\"));\n"
                "    if (!launched.contains(\"--quickPlaySingleplayer\\nMyWorld\")) throw new AssertionError(launched);\n"
                "    GloryData.clearLaunchArgs(priv);\n"
                "    if (new File(priv, \"glory-launch-args.txt\").exists()) throw new AssertionError(\"not cleared\");\n"
                "    File servers = new File(game, \"servers.dat\");\n"
                "    GloryData.addServer(servers, \"SMP\", \"play.example.net:25565\");\n"
                "    List<GloryData.ServerSummary> list = GloryData.loadServers(servers);\n"
                "    if (list.size() != 1 || !\"SMP\".equals(list.get(0).name)) throw new AssertionError(\"servers\");\n"
                "    GloryData.updateGraphicsKey(game, \"pickedShaderPack\", GloryData.BUNDLED_SHADER_ID);\n"
                "    GloryData.updateGraphicsKey(game, \"resolutionScale\", \"75\");\n"
                "    GloryData.GraphicsConfig gfx = GloryData.loadGraphicsConfig(game);\n"
                "    if (gfx.resolutionScale != 75 || !\"Starling Visuals\".equals(gfx.shaderDisplayName())) throw new AssertionError(\"gfx\");\n"
                "    GloryData.setModEnabled(game, \"wrench\", true, false);\n"
                "    if (!GloryData.readUtf8(new File(game, \"horizon-mods.txt\")).contains(\"mod.wrench:false\")) throw new AssertionError(\"mods\");\n"
                "    System.out.println(\"ok\");\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )

            sources = sorted(str(p) for p in src.rglob("*.java"))
            subprocess.run(
                ["javac", "--release", "8", "-nowarn", "-Xlint:-options", "-d", str(classes), *sources],
                check=True,
                capture_output=True,
                text=True,
            )
            ran = subprocess.run(
                ["java", "-cp", str(classes), "io.github.juanluci.starling.GloryDataChecks", str(work)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("ok", ran.stdout)


if __name__ == "__main__":
    unittest.main()
