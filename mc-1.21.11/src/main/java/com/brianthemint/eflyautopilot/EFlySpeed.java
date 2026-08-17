package com.brianthemint.eflyautopilot;

import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;

public class EFlySpeed extends Module {
    private final SettingGroup sgManual = settings.createGroup("Manual Speed");
    private final SettingGroup sgAdaptive = settings.createGroup("Adaptive Speed");

    private final Setting<Double> manualStep = sgManual.add(new DoubleSetting.Builder()
        .name("speed-step-bps").description("BPS added or removed by the speed keys.")
        .defaultValue(5).range(0, 100).sliderRange(0, 100).build());

    private final Setting<Keybind> speedUpKey = sgManual.add(new KeybindSetting.Builder()
        .name("speed-up-key").description("Raises Meteor EFly speed.")
        .defaultValue(Keybind.fromKey(GLFW_KEY_Z)).build());

    private final Setting<Keybind> speedDownKey = sgManual.add(new KeybindSetting.Builder()
        .name("speed-down-key").description("Lowers Meteor EFly speed.")
        .defaultValue(Keybind.fromKeys(GLFW_KEY_Z, GLFW_MOD_SHIFT)).build());

    private final Setting<Boolean> adaptive = sgAdaptive.add(new BoolSetting.Builder()
        .name("adaptive-speed").description("Raises speed while stable and backs off after server corrections.")
        .defaultValue(false).build());

    private final Setting<Double> startingBps = sgAdaptive.add(new DoubleSetting.Builder()
        .name("starting-bps").defaultValue(50).range(0, 500).sliderRange(0, 100)
        .visible(adaptive::get).build());

    private final Setting<Double> minimumBps = sgAdaptive.add(new DoubleSetting.Builder()
        .name("minimum-bps").defaultValue(20).range(0, 500).sliderRange(0, 100)
        .visible(adaptive::get).build());

    private final Setting<Double> maximumBps = sgAdaptive.add(new DoubleSetting.Builder()
        .name("maximum-bps").defaultValue(120).range(0, 500).sliderRange(0, 200)
        .visible(adaptive::get).build());

    private final Setting<Double> increaseBps = sgAdaptive.add(new DoubleSetting.Builder()
        .name("increase-step-bps").defaultValue(2).range(0.25, 100).sliderRange(0.25, 20)
        .visible(adaptive::get).build());

    private final Setting<Double> stableSeconds = sgAdaptive.add(new DoubleSetting.Builder()
        .name("stable-seconds").defaultValue(5).range(1, 30).sliderRange(1, 15)
        .visible(adaptive::get).build());

    private final Setting<Double> cooldownSeconds = sgAdaptive.add(new DoubleSetting.Builder()
        .name("correction-cooldown").defaultValue(8).range(1, 30).sliderRange(1, 20)
        .visible(adaptive::get).build());

    private final Setting<Double> settleMargin = sgAdaptive.add(new DoubleSetting.Builder()
        .name("settle-margin-bps").defaultValue(1).range(0.1, 5).sliderRange(0.1, 3)
        .visible(adaptive::get).build());

    private double currentBps;
    private double knownGood;
    private double knownBad;
    private int stableTicks;
    private int cooldownTicks;
    private boolean originalAcceleration;

    public EFlySpeed() {
        super(EFlyAutopilotAddon.ELYTRA, "efly-speed", "Separately controls Meteor Vanilla EFly speed with hotkeys or adaptive rubberband learning.");
    }

    @Override
    public void onActivate() {
        ElytraFly efly = Modules.get().get(ElytraFly.class);
        currentBps = efly.horizontalSpeed.get() * 20;
        originalAcceleration = efly.acceleration.get();
        knownGood = 0;
        knownBad = Double.POSITIVE_INFINITY;
        stableTicks = 0;
        cooldownTicks = 0;
        if (adaptive.get()) {
            currentBps = Mth.clamp(startingBps.get(), minimumBps.get(), maximumBps.get());
            efly.acceleration.set(false);
            applySpeed();
        }
    }

    @Override
    public void onDeactivate() {
        Modules.get().get(ElytraFly.class).acceleration.set(originalAcceleration);
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press || mc.screen != null) return;
        if (speedDownKey.get().matches(event.input)) changeSpeed(-manualStep.get());
        else if (speedUpKey.get().matches(event.input) && (speedUpKey.get().hasMods() || event.modifiers() == 0)) changeSpeed(manualStep.get());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!adaptive.get() || mc.player == null || !mc.player.isFallFlying()) return;
        Modules.get().get(ElytraFly.class).acceleration.set(false);
        if (cooldownTicks > 0) {
            cooldownTicks--;
            applySpeed();
            return;
        }
        if (++stableTicks < stableSeconds.get() * 20) {
            applySpeed();
            return;
        }

        knownGood = Math.max(knownGood, currentBps);
        if (Double.isFinite(knownBad)) {
            if (knownBad - knownGood > settleMargin.get()) currentBps = (knownGood + knownBad) * 0.5;
        } else currentBps = Math.min(maximumBps.get(), currentBps + increaseBps.get());
        stableTicks = 0;
        applySpeed();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!adaptive.get() || mc.player == null || !mc.player.isFallFlying()) return;
        if (!(event.packet instanceof ClientboundPlayerPositionPacket)) return;
        knownBad = Math.min(knownBad, currentBps);
        currentBps = Math.max(minimumBps.get(), knownGood > 0 ? knownGood : currentBps * 0.75);
        stableTicks = 0;
        cooldownTicks = (int) Math.round(cooldownSeconds.get() * 20);
        applySpeed();
        warning("Server correction detected. EFly speed reduced to (highlight)%.1f BPS(default).", currentBps);
    }

    private void changeSpeed(double amount) {
        ElytraFly efly = Modules.get().get(ElytraFly.class);
        currentBps = Mth.clamp(efly.horizontalSpeed.get() * 20 + amount, 0, 500);
        if (adaptive.get()) {
            knownGood = 0;
            knownBad = Double.POSITIVE_INFINITY;
            stableTicks = 0;
            cooldownTicks = 0;
        }
        applySpeed();
        info("EFly speed set to (highlight)%.1f BPS(default).", currentBps);
    }

    private void applySpeed() {
        Modules.get().get(ElytraFly.class).horizontalSpeed.set(currentBps / 20);
    }

    @Override
    public String getInfoString() {
        return String.format("%.1f BPS", currentBps);
    }
}
