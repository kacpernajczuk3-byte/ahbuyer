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
	private static final List<String> CONFIRM_WORDS = List.of("potwierd", "confirm");
	private static final List<String> CANCEL_WORDS = List.of("anuluj", "cancel");

	private static int cooldown = 0;
	private static long lastOpen = 0;
	private static long lastActionAt = 0;
	private static long lastWarn = 0;
	private static long awaitConfirmUntil = 0;
	private static int lastSyncId = -1;
	private static boolean ourSession = false;
	private static int pagesAhead = 0;

	private static String lastSig = "";
	private static String sigBefore = null; // != null -> czekamy aż strona się zmieni
	private static long waitSince = 0;

	public static void tick(MinecraftClient mc) {
		AhConfig cfg = AhConfig.get();
		if (!cfg.enabled || mc.player == null || mc.interactionManager == null) {
			ourSession = false;
			pagesAhead = 0;
			sigBefore = null;
			return;
		}
		if (cooldown > 0) { cooldown--; return; }

		long now = System.currentTimeMillis();
		Screen s = mc.currentScreen;

		// Brak otwartego GUI -> otwieramy AH od strony 1
		if (s == null) {
			pagesAhead = 0;
			sigBefore = null;
			lastSig = "";
			lastSyncId = -1;
			long since = now - lastOpen;
			if (since > 4000) ourSession = false;
			if (since >= 1500L) {
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
			lastSig = "";
			sigBefore = null;
			lastActionAt = now;
			cooldown = cfg.clickDelayTicks + 1;
			return;
		}

		// Strona jeszcze się ładuje (puste okno) - czekaj
		boolean loaded = false;
		for (int i = 0; i < size; i++) {
			if (!h.slots.get(i).getStack().isEmpty()) { loaded = true; break; }
		}
		if (!loaded) return;

		// 1) Okno potwierdzenia zakupu (przyciski Anuluj + Potwierdź)
		Slot cancel = findButton(h, size, CANCEL_WORDS);
		Slot confirm = findButton(h, size, CONFIRM_WORDS);
		if (cancel != null && confirm != null) {
			if (now < awaitConfirmUntil) {
				mc.interactionManager.clickSlot(h.syncId, confirm.id, 0, SlotActionType.PICKUP, mc.player);
				mc.player.sendMessage(Text.literal("§a[AH Buyer] Potwierdzono zakup"), true);
				awaitConfirmUntil = 0;
			} else {
				mc.interactionManager.clickSlot(h.syncId, cancel.id, 0, SlotActionType.PICKUP, mc.player);
			}
			pagesAhead = 0;
			sigBefore = null;
			lastSig = "";
			cooldown = cfg.clickDelayTicks + 2;
			lastActionAt = now;
			return;
		}

		// Czy to okno AH?
		boolean isAh = ourSession
				|| findButton(h, size, NEXT_WORDS) != null
				|| findButton(h, size, PREV_WORDS) != null
				|| findButton(h, size, AH_WORDS) != null;
		if (!isAh) return;

		// Czekamy aż zawartość okna się ustabilizuje
		String sig = signature(h, size);
		boolean settled = sig.equals(lastSig);
		lastSig = sig;
		if (!settled) return;

		// Po kliknięciu strony czekamy aż zawartość faktycznie się zmieni
		if (sigBefore != null) {
			if (!sig.equals(sigBefore)) sigBefore = null;
			else if (now - waitSince > 3500) sigBefore = null;
			else return;
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
			awaitConfirmUntil = now + 8000;
			sigBefore = null;
			lastSig = "";
			cooldown = cfg.clickDelayTicks;
			lastActionAt = now;
			return;
		}

		// 3) Jesteśmy do przodu -> wracamy na stronę 1
		if (pagesAhead > 0) {
			Slot prev = findButton(h, size, PREV_WORDS);
			if (prev != null) {
				mc.interactionManager.clickSlot(h.syncId, prev.id, 0, SlotActionType.PICKUP, mc.player);
				mc.player.sendMessage(Text.literal("§e[AH Buyer] Poprzednia strona"), true);
				pagesAhead--;
				sigBefore = sig;
				waitSince = now;
				cooldown = cfg.clickDelayTicks;
				lastActionAt = now;
				return;
			}
			if (now - lastActionAt > 4000) {
				pagesAhead = 0;
				mc.player.closeHandledScreen();
				cooldown = 10;
			}
			return;
		}

		// 4) Jesteśmy na stronie 1 -> kliknij następną
		Slot next = findButton(h, size, NEXT_WORDS);
		if (next != null) {
			mc.interactionManager.clickSlot(h.syncId, next.id, 0, SlotActionType.PICKUP, mc.player);
			mc.player.sendMessage(Text.literal("§e[AH Buyer] Nastepna strona"), true);
			pagesAhead = 1;
			sigBefore = sig;
			waitSince = now;
			cooldown = cfg.clickDelayTicks;
			lastActionAt = now;
		} else if (now - lastWarn > 5000) {
			lastWarn = now;
			mc.player.sendMessage(Text.literal("§c[AH Buyer] Nie widze przycisku Nastepna strona"), true);
		}
	}

	private static String signature(GenericContainerScreenHandler h, int size) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < size; i++) {
			ItemStack st = h.slots.get(i).getStack();
			if (st.isEmpty()) { sb.append('-'); continue; }
			sb.append(Registries.ITEM.getId(st.getItem()).getPath())
					.append(st.getCount())
					.append(st.getName().getString());
			LoreComponent lore = st.get(DataComponentTypes.LORE);
			if (lore != null) {
				for (Text t : lore.lines()) sb.append(t.getString());
			}
			sb.append('|');
		}
		return sb.toString();
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
