package com.brianthemint.eflyautopilot;

import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.network.chat.Component;

public class EFlyLogout extends Module {
    public EFlyLogout() {
        super(EFlyAutopilotAddon.ELYTRA, "logout", "A bind-only module that immediately disconnects from the current server.");
    }

    @Override
    public void onActivate() {
        if (mc.getConnection() == null) {
            toggle();
            return;
        }

        // Reset before disconnecting so the assigned bind remains a reusable action.
        toggle();
        mc.getConnection().getConnection().disconnect(Component.literal("Manual logout key pressed"));
    }
}
