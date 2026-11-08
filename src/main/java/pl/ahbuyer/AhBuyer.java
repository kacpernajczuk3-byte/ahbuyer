package pl.ahbuyer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.List;

public class AhBuyer {
	private static final List<String> NEXT_WORDS = List.of("następn", "nastepn", "next", "dalej", "→", "»", ">>");
	private static final List<String> PREV_WORDS = List.of("poprzedni", "previous", "prev", "wstecz", "←", "«", "<<");
	private static final List<String> AH_WORDS = List.of("twoje przedmioty", "sortowanie", "filtrowanie", "wyszukiwanie");

	private static int cooldown = 0;
	private static long lastOpen = 0;
	private static long lastActionAt = 0;
	private static int lastSyncId = -1;
	private static boolean ourSession = false;
	private static boolean pendingPrev = false;

	public static void tick(MinecraftClient mc) {
		AhConfig cfg = AhConfig.get();
		if (!cfg.enabled || mc.player == null || mc.interactionManager == null) {
			ourSession = false;
			pendingPrev = false;
			return;
		}
		if (cooldown > 0) { cooldown--; return; }

		long now = System.currentTimeMillis();
		Screen s = mc.currentScreen;

		// Brak otwartego GUI -> otwieramy AH
		if (s == null) {
			pendingPrev = false;
			lastSyncId = -1;
			long since = now - lastOpen;
			if (since > 4000) ourSession = false;
			if (since >= Math.max(1500L, (long) (cfg.refreshSeconds * 1000))) {
				mc.player.networkHandler.sendChatCommand(cfg.openCommand);
				lastOpen = now;
				ourSession = true;
			}
			return;
		}

		if (!(s instanceof GenericContainerScreen gcs)) return;

		GenericContainerScreenHandler h = gcs.getScreenHandler();
		int size = h.getRows() * 9;

		// Nowe okno - poczekaj aż przyjdą itemy
		if (h.syncId != lastSyncId) {
			lastSyncId = h.syncId;
			lastActionAt = now;
			cooldown = cfg.clickDelayTicks + 4;
			return;
		}

		// Czy to okno AH? (po przyciskach) albo otworzone przez nas
		boolean isAh = ourSession
				|| findButton(h, size, NEXT_WORDS) != null
				|| findButton(h, size, PREV_WORDS) != null
				|| findButton(h, size, AH_WORDS) != null;
		if (!isAh) return;

		// 1) Okno potwierdzenia?
		for (int i = 0; i < size; i++) {
			Slot slot = h.slots.get(i);
			ItemStack st = slot.getStack();
			if (st.isEmpty()) continue;
			String id = Registries.ITEM.getId(st.getItem()).getPath();
			boolean greenish = id.contains("lime") || id.contains("green") || id.equals("emerald_block");
			if (!greenish) continue;
			String name = st.getName().getString().toLowerCase();
			for (String w : cfg.confirmWords) {
				if (!w.isBlank() && name.contains(w.toLowerCase())) {
					mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
					cooldown = cfg.clickDelayTicks;
					return;
				}
			}
		}

		// 2) Szukamy najtańszej pasującej oferty na tej stronie
		Slot best = null;
		double bestPrice = Double.MAX_VALUE;
		String bestName = "";
		for (int i = 0; i < size; i++) {
			Slot slot = h.slots.get(i);
			ItemStack st = slot.getStack();
			if (st.isEmpty()) continue;
			String id = Registries.ITEM.getId(st.getItem()).toString();
			for (AhConfig.Entry e : cfg.entries) {
				if (e.item == null || !e.item.equalsIgnoreCase(id)) continue;
				double price = readPrice(st, cfg.priceKeyword);
				if (price >= 0 && price <= e.maxPrice && price < bestPrice) {
					best = slot; bestPrice = price; bestName = st.getCount() + "x " + st.getName().getString();
				}
			}
		}

		if (best != null) {
			mc.interactionManager.clickSlot(h.syncId, best.id, 0, SlotActionType.PICKUP, mc.player);
			mc.player.sendMessage(Text.literal("§a[AH Buyer] §fKlik: " + bestName + " za " + bestPrice), false);
			pendingPrev = false;
			cooldown = cfg.clickDelayTicks;
			lastActionAt = now;
			return;
		}

		// 3) Jesteśmy na stronie 2 -> wróć na poprzednią
		if (pendingPrev) {
			pendingPrev = false;
			Slot prev = findButton(h, size, PREV_WORDS);
			if (prev != null) {
				mc.interactionManager.clickSlot(h.syncId, prev.id, 0, SlotActionType.PICKUP, mc.player);
				mc.player.sendMessage(Text.literal("§e[AH Buyer] Poprzednia strona"), true);
				cooldown = cfg.clickDelayTicks + 4;
				lastActionAt = now;
				return;
			}
			mc.player.closeHandledScreen();
			cooldown = 10;
			return;
		}

		// 4) Odświeżenie: kliknij następną stronę
		long wait = Math.max(600L, (long) (cfg.refreshSeconds * 1000 / 3));
		if (now - lastActionAt >= wait) {
			Slot next = findButton(h, size, NEXT_WORDS);
			if (next != null) {
				mc.interactionManager.clickSlot(h.syncId, next.id, 0, SlotActionType.PICKUP, mc.player);
				mc.player.sendMessage(Text.literal("§e[AH Buyer] Nastepna strona"), true);
				pendingPrev = true;
				cooldown = cfg.clickDelayTicks + 6;
				lastActionAt = now;
				return;
			}
			if (now - lastActionAt >= (long) (cfg.refreshSeconds * 1000)) {
				mc.player.sendMessage(Text.literal("§c[AH Buyer] Nie widze przycisku Nastepna strona"), true);
				mc.player.closeHandledScreen();
				cooldown = 10;
			}
		}
	}

	private static Slot findButton(GenericContainerScreenHandler h, int size, List<String> words) {
		for (int i = 0; i < size; i++) {
			Slot slot = h.slots.get(i);
			ItemStack st = slot.getStack();
			if (st.isEmpty()) continue;
			String name = st.getName().getString().toLowerCase();
			for (String w : words) {
				if (name.contains(w)) return slot;
			}
		}
		return null;
	}

	private static double readPrice(ItemStack st, String keyword) {
		LoreComponent lore = st.get(DataComponentTypes.LORE);
		if (lore == null) return -1;
		String kw = keyword == null ? "" : keyword.toLowerCase();
		for (Text line : lore.lines()) {
			String str = line.getString();
			String low = str.toLowerCase();
			int idx = kw.isEmpty() ? 0 : low.indexOf(kw);
			if (idx < 0) continue;
			double p = AhConfig.parseNumber(str.substring(idx + kw.length()));
			if (p >= 0) return p;
		}
		return -1;
	}
}
