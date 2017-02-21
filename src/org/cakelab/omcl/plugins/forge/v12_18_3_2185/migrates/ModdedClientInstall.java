package org.cakelab.omcl.plugins.forge.v12_18_3_2185.migrates;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.swing.JOptionPane;

import org.cakelab.omcl.plugins.interfaces.ServicesListener;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import argo.format.PrettyJsonFormatter;
import argo.jdom.JdomParser;
import argo.jdom.JsonField;
import argo.jdom.JsonNode;
import argo.jdom.JsonNodeFactories;
import argo.jdom.JsonRootNode;
import argo.jdom.JsonStringNode;
import net.minecraftforge.installer.Artifact;
import net.minecraftforge.installer.DownloadUtils;
import net.minecraftforge.installer.IMonitor;
import net.minecraftforge.installer.LibraryInfo;
import net.minecraftforge.installer.MirrorData;
import net.minecraftforge.installer.OptionalLibrary;
import net.minecraftforge.installer.VersionInfo;

public class ModdedClientInstall {
	private static final Charset PROFILES_CHARSET = Charset.defaultCharset();
	private List<Artifact> grabbed;
	private ServicesListener listener;
	private Predicate<String> optionals = new Predicate<String>(){
		@Override
		public boolean apply(String s) {
			return false;
		}
	};
	public boolean run(File target, final ServicesListener listener) {
		this.listener = listener;
		if (!target.exists()) {
			showForgeError("There is no minecraft installation at this location!", "Error");
			return false;
		}
		File launcherProfiles = new File(target, "launcher_profiles.json");
		if (!launcherProfiles.exists()) {
			showForgeError(
					"There is no minecraft launcher profile at this location, you need to run the launcher first!",
					"Error");
			return false;
		}

		File versionRootDir = new File(target, "versions");
		File versionTarget = new File(versionRootDir, VersionInfo.getVersionTarget());
		if ((!versionTarget.mkdirs()) && (!versionTarget.isDirectory())) {
			if (!versionTarget.delete()) {
				showForgeError("There was a problem with the launcher version data. You will need to clear "
						+ versionTarget.getAbsolutePath() + " manually", "Error");
			} else {
				versionTarget.mkdirs();
			}
		}

		File librariesDir = new File(target, "libraries");

		IMonitor monitor = new IMonitor() {
			int max = 0;
			private String note;

			@Override
			public void setMaximum(int paramInt) {
				this.max = paramInt;
			}

			@Override
			public void setNote(String paramString) {
				listener.info(paramString, null);
			}

			@Override
			public void setProgress(int paramInt) {
				listener.updateProgress(max, paramInt, (float) paramInt / max, note);
			}

			@Override
			public void close() {
				listener.endProgress();
			}

		};
		List<LibraryInfo> libraries = VersionInfo.getLibraries("clientreq", optionals);
		monitor.setMaximum(libraries.size() + 3);
		int progress = 3;

		File versionJsonFile = new File(versionTarget, VersionInfo.getVersionTarget() + ".json");

		if (!VersionInfo.isInheritedJson()) {
			File clientJarFile = new File(versionTarget, VersionInfo.getVersionTarget() + ".jar");
			File minecraftJarFile = VersionInfo.getMinecraftFile(versionRootDir);

			try {
				boolean delete = false;
				monitor.setNote("Considering minecraft client jar");
				monitor.setProgress(1);

				if (!minecraftJarFile.exists()) {
					minecraftJarFile = File.createTempFile("minecraft_client", ".jar");
					delete = true;
					monitor.setNote(String.format("Downloading minecraft client version %s",
							new Object[] { VersionInfo.getMinecraftVersion() }));
					String clientUrl = String
							.format("https://s3.amazonaws.com/Minecraft.Download/versions/{MCVER}/{MCVER}.jar"
									.replace("{MCVER}", VersionInfo.getMinecraftVersion()), new Object[0]);
					System.out.println("  Temp File: " + minecraftJarFile.getAbsolutePath());

					if (!DownloadUtils.downloadFileEtag("minecraft server", minecraftJarFile, clientUrl)) {
						minecraftJarFile.delete();
						showForgeError(
										"Downloading minecraft failed, invalid e-tag checksum.\nTry again, or use the official launcher to run Minecraft "
												+ VersionInfo.getMinecraftVersion() + " first.",
										"Error downloading");

						return false;
					}
					monitor.setProgress(2);
				}

				if (VersionInfo.getStripMetaInf()) {
					monitor.setNote("Copying and filtering minecraft client jar");
					copyAndStrip(minecraftJarFile, clientJarFile);
					monitor.setProgress(3);
				} else {
					monitor.setNote("Copying minecraft client jar");
					Files.copy(minecraftJarFile, clientJarFile);
					monitor.setProgress(3);
				}

				if (delete) {
					minecraftJarFile.delete();
				}
			} catch (IOException e1) {
				JOptionPane.showMessageDialog(null,
						"You need to run the version " + VersionInfo.getMinecraftVersion() + " manually at least once",
						"Error", 0);
				return false;
			}
		}

		File targetLibraryFile = VersionInfo.getLibraryPath(librariesDir);
		grabbed = Lists.newArrayList();
		List<Artifact> bad = Lists.newArrayList();
		progress = DownloadUtils.downloadInstalledLibraries(true, librariesDir, monitor, libraries, progress,
				grabbed, bad);

		monitor.close();
		if (bad.size() > 0) {
			String list = com.google.common.base.Joiner.on("\n").join(bad);
			showForgeError("These libraries failed to download. Try again.\n" + list, "Error downloading");
			return false;
		}

		if ((!targetLibraryFile.getParentFile().mkdirs()) && (!targetLibraryFile.getParentFile().isDirectory())) {
			if (!targetLibraryFile.getParentFile().delete()) {
				showForgeError("There was a problem with the launcher version data. You will need to clear "
								+ targetLibraryFile.getAbsolutePath() + " manually", "Error");
				return false;
			}

			targetLibraryFile.getParentFile().mkdirs();
		}

		String modListType = VersionInfo.getModListType();
		File modListFile = new File(target, "mods/mod_list.json");

		JsonRootNode versionJson = JsonNodeFactories.object(VersionInfo.getVersionInfo().getFields());

		if ("absolute".equals(modListType)) {
			modListFile = new File(versionTarget, "mod_list.json");
			JsonStringNode node = (JsonStringNode) versionJson.getNode(new Object[] { "minecraftArguments" });
			try {
				Field value = JsonStringNode.class.getDeclaredField("value");
				value.setAccessible(true);
				String args = (String) value.get(node);
				value.set(node, args + " --modListFile \"absolute:" + modListFile.getAbsolutePath() + "\"");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (!"none".equals(modListType)) {
			if (!OptionalLibrary.saveModListJson(librariesDir, modListFile, VersionInfo.getOptionals(), optionals)) {
				JOptionPane.showMessageDialog(null, "Failed to write mod_list.json, optional mods may not be loaded.",
						"Error", 0);
			}
		}

		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			Writer writer = new OutputStreamWriter(bos, Charsets.UTF_8);
			PrettyJsonFormatter.fieldOrderPreservingPrettyJsonFormatter().format(versionJson, writer);
			writer.close();

			byte[] output = bos.toByteArray();

			Files.write(output, versionJsonFile);
		} catch (Exception e) {
			showForgeError("There was a problem writing the launcher version data,  is it write protected?", "Error");
			return false;
		}

		try {
			VersionInfo.extractFile(targetLibraryFile);
		} catch (IOException e) {
			showForgeError("There was a problem writing the system library file", "Error");
			return false;
		}

		JdomParser parser = new JdomParser();
		JsonRootNode jsonProfileData;
		try {
			jsonProfileData = parser.parse(Files.newReader(launcherProfiles, PROFILES_CHARSET));
		} catch (argo.saj.InvalidSyntaxException e) {
			showForgeError("The launcher profile file is corrupted. Re-run the minecraft launcher to fix it!", "Error");			return false;
		} catch (Exception e) {
			throw com.google.common.base.Throwables.propagate(e);
		}

		HashMap<JsonStringNode, JsonNode> profileCopy = Maps
				.newHashMap(jsonProfileData.getNode(new Object[] { "profiles" }).getFields());
		HashMap<JsonStringNode, JsonNode> rootCopy = Maps.newHashMap(jsonProfileData.getFields());
		if (profileCopy.containsKey(JsonNodeFactories.string(VersionInfo.getProfileName()))) {
			HashMap<JsonStringNode, JsonNode> forgeProfileCopy = Maps.newHashMap(
					((JsonNode) profileCopy.get(JsonNodeFactories.string(VersionInfo.getProfileName()))).getFields());
			forgeProfileCopy.put(JsonNodeFactories.string("name"),
					JsonNodeFactories.string(VersionInfo.getProfileName()));
			forgeProfileCopy.put(JsonNodeFactories.string("lastVersionId"),
					JsonNodeFactories.string(VersionInfo.getVersionTarget()));
		} else {
			JsonField[] fields = {
					JsonNodeFactories.field("name", JsonNodeFactories.string(VersionInfo.getProfileName())),
					JsonNodeFactories.field("lastVersionId",
							JsonNodeFactories.string(VersionInfo.getVersionTarget())) };

			profileCopy.put(JsonNodeFactories.string(VersionInfo.getProfileName()), JsonNodeFactories.object(fields));
		}
		JsonRootNode profileJsonCopy = JsonNodeFactories.object(profileCopy);
		rootCopy.put(JsonNodeFactories.string("profiles"), profileJsonCopy);

		jsonProfileData = JsonNodeFactories.object(rootCopy);

		try {
			BufferedWriter newWriter = Files.newWriter(launcherProfiles, PROFILES_CHARSET);
			PrettyJsonFormatter.fieldOrderPreservingPrettyJsonFormatter().format(jsonProfileData, newWriter);
			newWriter.close();
		} catch (Exception e) {
			showForgeError("There was a problem writing the launch profile,  is it write protected?", "Error");
			return false;
		}

		File successIndicator = new File(versionTarget, ".success");
		try {
			successIndicator.createNewFile();
		} catch (IOException e) {
			showForgeError("could not create success indicator file " + successIndicator.getAbsolutePath(), "Error");
		}
		return true;
	}

	private void showForgeError(String details, String message) {
		listener.showError("Forge: " + message + ".", details);
	}

	private void copyAndStrip(File sourceJar, File targetJar) throws IOException {
		ZipFile in = new ZipFile(sourceJar);
		ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(targetJar)));

		for (ZipEntry e : Collections.list(in.entries())) {
			if (e.isDirectory()) {
				out.putNextEntry(e);
			} else if (!e.getName().startsWith("META-INF")) {

				ZipEntry n = new ZipEntry(e.getName());
				n.setTime(e.getTime());
				out.putNextEntry(n);
				out.write(readEntry(in, e));
			}
		}

		in.close();
		out.close();
	}

	private static byte[] readEntry(ZipFile inFile, ZipEntry entry) throws IOException {
		return readFully(inFile.getInputStream(entry));
	}

	private static byte[] readFully(InputStream stream) throws IOException {
		byte[] data = new byte['က'];
		ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
		int len;
		do {
			len = stream.read(data);
			if (len > 0) {
				entryBuffer.write(data, 0, len);
			}
		} while (len != -1);

		return entryBuffer.toByteArray();
	}

	public boolean isPathValid(File targetDir) {
		if (targetDir.exists()) {
			File launcherProfiles = new File(targetDir, "launcher_profiles.json");
			return launcherProfiles.exists();
		}
		return false;
	}

	public String getFileError(File targetDir) {
		if (targetDir.exists()) {
			return "The directory is missing a launcher profile. Please run the minecraft launcher first";
		}

		return "There is no minecraft directory set up. Either choose an alternative, or run the minecraft launcher to create one";
	}

	public String getSuccessMessage() {
		if (grabbed.size() > 0) {
			return String.format(
					"Successfully installed client profile %s for version %s into launcher and grabbed %d required libraries",
					new Object[] { VersionInfo.getProfileName(), VersionInfo.getVersion(),
							Integer.valueOf(grabbed.size()) });
		}
		return String.format("Successfully installed client profile %s for version %s into launcher",
				new Object[] { VersionInfo.getProfileName(), VersionInfo.getVersion() });
	}

	public String getSponsorMessage() {
		return MirrorData.INSTANCE.hasMirrors()
				? String.format("<html><a href='%s'>Data kindly mirrored by %s</a></html>",
						new Object[] { MirrorData.INSTANCE.getSponsorURL(), MirrorData.INSTANCE.getSponsorName() })
				: null;
	}
}
