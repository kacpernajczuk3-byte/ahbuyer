package pl.ahbuyer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class AhBuyerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		KeyBinding menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.ahbuyer.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.ahbuyer"));
		KeyBinding toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.ahbuyer.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.ahbuyer"));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (menuKey.wasPressed()) mc.setScreen(new AhScreen());
			while (toggleKey.wasPressed()) {
				AhConfig cfg = AhConfig.get();
				cfg.enabled = !cfg.enabled;
				cfg.save();
				if (mc.player != null) {
					mc.player.sendMessage(Text.literal(cfg.enabled ? "§a[AH Buyer] WŁĄCZONY" : "§c[AH Buyer] WYŁĄCZONY"), true);
				}
			}
			AhBuyer.tick(mc);
		});
	}
}
