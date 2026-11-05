package pl.ahbuyer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

public class AhScreen extends Screen {
	private TextFieldWidget itemField, priceField;
	private String status = "";

	public AhScreen() {
		super(Text.literal("AH Buyer"));
	}

	private Text toggleText() {
		return Text.literal(AhConfig.get().enabled ? "Auto-kupowanie: §aWŁĄCZONE" : "Auto-kupowanie: §cWYŁĄCZONE");
	}

	@Override
	protected void init() {
		AhConfig cfg = AhConfig.get();
		int cx = width / 2;

		addDrawableChild(ButtonWidget.builder(toggleText(), b -> {
			cfg.enabled = !cfg.enabled;
			b.setMessage(toggleText());
			cfg.save();
		}).dimensions(cx - 100, 22, 200, 20).build());

		List<AhConfig.Entry> list = cfg.entries;
		int rows = Math.min(list.size(), Math.max(1, (height - 150) / 22));
		for (int i = 0; i < rows; i++) {
			final int idx = i;
			addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
				cfg.entries.remove(idx);
				cfg.save();
				clearAndInit();
			}).dimensions(cx + 130, 52 + i * 22, 20, 20).build());
		}

		itemField = new TextFieldWidget(textRenderer, cx - 150, height - 70, 170, 20, Text.literal("item"));
		itemField.setMaxLength(100);
		itemField.setPlaceholder(Text.literal("np. diamond"));
		addDrawableChild(itemField);

		priceField = new TextFieldWidget(textRenderer, cx + 25, height - 70, 75, 20, Text.literal("cena"));
		priceField.setMaxLength(20);
		priceField.setPlaceholder(Text.literal("max cena"));
		addDrawableChild(priceField);

		addDrawableChild(ButtonWidget.builder(Text.literal("Dodaj"), b -> addEntry())
				.dimensions(cx + 105, height - 70, 45, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij"), b -> close())
				.dimensions(cx - 50, height - 35, 100, 20).build());
	}

	private void addEntry() {
		String raw = itemField.getText().trim().toLowerCase().replace(' ', '_');
		if (!raw.contains(":")) raw = "minecraft:" + raw;
		Identifier id = Identifier.tryParse(raw);
		if (id == null || !Registries.ITEM.containsId(id)) {
			status = "§cNie ma takiego przedmiotu: " + raw;
			return;
		}
		double price = AhConfig.parseNumber(priceField.getText());
		if (price < 0) {
			status = "§cZła cena (przykłady: 1500, 1.5k, 2m)";
			return;
		}
		AhConfig cfg = AhConfig.get();
		cfg.entries.removeIf(e -> raw.equals(e.item));
		cfg.entries.add(new AhConfig.Entry(id.toString(), price));
		cfg.save();
		status = "§aDodano: " + id;
		clearAndInit();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		int cx = width / 2;
		ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 8, 0xFFFFFF);

		List<AhConfig.Entry> list = AhConfig.get().entries;
		int rows = Math.min(list.size(), Math.max(1, (height - 150) / 22));
		for (int i = 0; i < rows; i++) {
			AhConfig.Entry e = list.get(i);
			ctx.drawTextWithShadow(textRenderer, e.item + "  ≤  " + (long) e.maxPrice, cx - 150, 58 + i * 22, 0xFFFFFF);
		}
		if (list.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Brak przedmiotów - dodaj poniżej"), cx, 60, 0xAAAAAA);
		}
		ctx.drawTextWithShadow(textRenderer, "Przedmiot", cx - 150, height - 82, 0xAAAAAA);
		ctx.drawTextWithShadow(textRenderer, "Cena", cx + 25, height - 82, 0xAAAAAA);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(status), cx, height - 95, 0xFFFFFF);
	}
}
