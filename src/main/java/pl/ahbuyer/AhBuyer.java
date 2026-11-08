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

	private static final long CONFIRM_DELAY_MS = 350;  // czekanie przed kliknięciem "Potwierdź"
	private static final long CONFIRM_RETRY_MS = 1200; // ponowienie, jeśli okno dalej wisi

	private static long lastOpen = 0;
	private static long lastWarn = 0;
	private static long awaitConfirmUntil = 0;
	private static long noBuyUntil = 0;
	private static double expectedMax = 0;
	private static int lastSyncId = -1;
	private static boolean ourSession = false;

	private static long confirmSeenAt = 0;
	private static long lastConfirmClick = 0;
	private static int confirmClicks = 0;

	private static int pendingKind = 0; // 0 brak, 1 następna, 2 poprzednia
	private static String sigBefore = null;
	private static long waitSince = 0;
	private static boolean atPage1 = true;
	private static int prevFails = 0;

	private static void resolve(boolean changed) {
		if (pendingKind == 1 && changed) {
			atPage1 = false;
		} else if (pendingKind == 2) {
			if (changed) { atPage1 = true; prevFails = 0; }
			else if (++prevFails >= 2) { atPage1 = true; prevFails = 0; }
		}
		pendingKind = 0;
		sigBefore = null;
	}

	private static void reset() {
		pendingKind = 0;
		sigBefore = null;
		atPage1 = true;
		prevFails = 0;
	}

	public static void tick(MinecraftClient mc) {
		AhConfig cfg = AhConfig.get();
		if (!cfg.enabled || mc.player == null || mc.interactionManager == null) {
			ourSession = false;
			confirmSeenAt = 0;
			confirmClicks = 0;
			reset();
			return;
		}

		long now = System.currentTimeMillis();
		Screen s = mc.currentScreen;

		// Brak otwartego GUI -> otwieramy AH
		if (s == null) {
			reset();
			confirmSeenAt = 0;
			confirmClicks = 0;
			lastSyncId = -1;
			long since = now - lastOpen;
			if (since > 3000) ourSession = false;
			if (since >= 700L) {
				mc.player.networkHandler.sendChatCommand(cfg.openCommand);
				lastOpen = now;
				ourSession = true;
			}
			return;
		}

		if (!(s instanceof GenericContainerScreen gcs)) return;

		GenericContainerScreenHandler h = gcs.getScreenHandler();
		int size = h.getRows() * 9;

		if (h.syncId != lastSyncId) {
			lastSyncId = h.syncId;
			confirmSeenAt = 0;
			if (pendingKind != 0) resolve(true);
		}

		// Okno jeszcze puste - czekaj
		boolean loaded = false;
		for (int i = 0; i < size; i++) {
			if (!h.slots.get(i).getStack().isEmpty()) { loaded = true; break; }
		}
		if (!loaded) return;

		// 1) Okno potwierdzenia zakupu (Anuluj + Potwierdź)
		Slot cancel = findButton(h, size, CANCEL_WORDS);
		Slot confirm = findButton(h, size, CONFIRM_WORDS);
		if (cancel != null && confirm != null) {
			if (confirmSeenAt == 0) confirmSeenAt = now;
			double cp = readPrice(confirm.getStack(), cfg.priceKeyword);
			boolean ours = now < awaitConfirmUntil;
			boolean priceOk = cp < 0 || cp <= expectedMax;

			if (ours && priceOk) {
				if (now - confirmSeenAt < CONFIRM_DELAY_MS) return;        // jeszcze za wcześnie
				if (now - lastConfirmClick < CONFIRM_RETRY_MS) return;     // czekamy na serwer
				if (confirmClicks >= 3) {
					mc.interactionManager.clickSlot(h.syncId, cancel.id, 0, SlotActionType.PICKUP, mc.player);
					awaitConfirmUntil = 0;
					confirmClicks = 0;
					return;
				}
				mc.interactionManager.clickSlot(h.syncId, confirm.id, 0, SlotActionType.PICKUP, mc.player);
				mc.player.sendMessage(Text.literal("§a[AH Buyer] Potwierdzam zakup"), true);
				lastConfirmClick = now;
				confirmClicks++;
				return;
			}

			// nie nasz zakup albo za wysoka cena -> anuluj
			if (now - confirmSeenAt < 150) return;
			mc.interactionManager.clickSlot(h.syncId, cancel.id, 0, SlotActionType.PICKUP, mc.player);
			if (ours) mc.player.sendMessage(Text.literal("§c[AH Buyer] Cena za wysoka - anulowano"), true);
			awaitConfirmUntil = 0;
			confirmSeenAt = 0;
			noBuyUntil = now + 300;
			reset();
			return;
		} else if (confirmClicks > 0 || confirmSeenAt != 0) {
			// okno potwierdzenia zniknęło - zakup załatwiony
			confirmSeenAt = 0;
			confirmClicks = 0;
			awaitConfirmUntil = 0;
			noBuyUntil = now + 300;
			reset();
		}

		// Czy to okno AH?
		boolean isAh = ourSession
				|| findButton(h, size, NEXT_WORDS) != null
				|| findButton(h, size, PREV_WORDS) != null
				|| findButton(h, size, AH_WORDS) != null;
		if (!isAh) return;

		// Czekamy na efekt ostatniego kliknięcia strony
		if (pendingKind != 0) {
			String sig = signature(h, size);
			if (!sig.equals(sigBefore)) resolve(true);
			else if (now - waitSince > 600) resolve(false);
			else return;
		}

		// 2) Kupno: najtańsza pasująca oferta na tej stronie
		if (now >= noBuyUntil) {
			Slot best = null;
			double bestPrice = Double.MAX_VALUE;
			double bestMax = 0;
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
						best = slot; bestPrice = price; bestMax = e.maxPrice;
						bestName = st.getCount() + "x " + st.getName().getString();
					}
				}
			}
			if (best != null) {
				mc.interactionManager.clickSlot(h.syncId, best.id, 0, SlotActionType.PICKUP, mc.player);
				mc.player.sendMessage(Text.literal("§a[AH Buyer] §fKlik: " + bestName + " za " + bestPrice), false);
				awaitConfirmUntil = now + 8000;
				expectedMax = bestMax;
				noBuyUntil = now + 1500;
				confirmSeenAt = 0;
				confirmClicks = 0;
				return;
			}
		}

		// 3) Przerzucanie stron na podstawie tego, co widać w oknie
		Slot prev = findButton(h, size, PREV_WORDS);
		Slot next = findButton(h, size, NEXT_WORDS);

		if (!atPage1 && prev != null) {
			sigBefore = signature(h, size);
			mc.interactionManager.clickSlot(h.syncId, prev.id, 0, SlotActionType.PICKUP, mc.player);
			pendingKind = 2;
			waitSince = now;
		} else if (next != null) {
			sigBefore = signature(h, size);
			mc.interactionManager.clickSlot(h.syncId, next.id, 0, SlotActionType.PICKUP, mc.player);
			pendingKind = 1;
			waitSince = now;
		} else if (prev != null) {
			sigBefore = signature(h, size);
			mc.interactionManager.clickSlot(h.syncId, prev.id, 0, SlotActionType.PICKUP, mc.player);
			pendingKind = 2;
			waitSince = now;
		} else if (now - lastWarn > 5000) {
			lastWarn = now;
			mc.player.sendMessage(Text.literal("§c[AH Buyer] Nie widze przyciskow stron"), true);
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
