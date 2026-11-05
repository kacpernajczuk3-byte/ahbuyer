package pl.ahbuyer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AhConfig {
	public static class Entry {
		public String item;      // np. "minecraft:diamond"
		public double maxPrice;  // maksymalna cena za CAŁY stack/ofertę
		public Entry() {}
		public Entry(String item, double maxPrice) { this.item = item; this.maxPrice = maxPrice; }
	}

	public boolean enabled = false;
	public String openCommand = "ah open";      // bez ukośnika
	public String priceKeyword = "cena";        // słowo w lore, po którym jest cena (małe litery)
	public List<String> confirmWords = new ArrayList<>(List.of("potwierd", "confirm", "kup", "tak", "accept"));
	public double refreshSeconds = 3.0;         // co ile odświeżać AH
	public int clickDelayTicks = 4;             // przerwa między kliknięciami (20 ticków = 1 s)
	public List<Entry> entries = new ArrayList<>();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("ahbuyer.json");
	private static AhConfig INSTANCE;

	public static AhConfig get() {
		if (INSTANCE == null) load();
		return INSTANCE;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				INSTANCE = GSON.fromJson(Files.readString(PATH), AhConfig.class);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (INSTANCE == null) INSTANCE = new AhConfig();
		INSTANCE.save();
	}

	public void save() {
		try {
			Files.writeString(PATH, GSON.toJson(this));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static final Pattern NUM = Pattern.compile("(\\d[\\d.,\\s]*)\\s*([kKmMbB])?(?![a-zA-Z])");

	/** Parsuje liczby typu 1500, 1,500, 1.500, 1.5k, 2m, 3b. Zwraca -1 gdy się nie da. */
	public static double parseNumber(String s) {
		if (s == null) return -1;
		Matcher m = NUM.matcher(s);
		if (!m.find()) return -1;
		String n = m.group(1).replaceAll("\\s", "").replaceAll("[.,]+$", "");
		try {
			if (n.matches("\\d{1,3}([.,]\\d{3})+")) n = n.replaceAll("[.,]", "");
			else n = n.replace(',', '.');
			double v = Double.parseDouble(n);
			String suf = m.group(2);
			if (suf != null) {
				switch (suf.toLowerCase()) {
					case "k" -> v *= 1_000;
					case "m" -> v *= 1_000_000;
					case "b" -> v *= 1_000_000_000;
				}
			}
			return v;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
