package com.brianthemint.eflyautopilot;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Category;

public class EFlyAutopilotAddon extends MeteorAddon {
    public static final Category ELYTRA = new Category("Elytra");

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(ELYTRA);
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new EFlyAutopilot());
        Modules.get().add(new VectorElytra());
        Modules.get().add(new EFlySpeed());
        Modules.get().add(new EFlyUnstuck());
        Modules.get().add(new EFlyAutoStop());
        Modules.get().add(new EFlyLogout());
    }

    @Override
    public String getPackage() {
        return "com.brianthemint.eflyautopilot";
    }
}
