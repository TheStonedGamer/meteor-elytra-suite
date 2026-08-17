package com.brianthemint.eflyautopilot;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.phys.Vec3;

public class EFlyUnstuck extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> stuckSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("stuck-seconds")
        .description("How long directional input may produce almost no movement before EFly is restarted.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 10)
        .build());

    private final Setting<Double> stuckDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("stuck-distance-per-tick")
        .description("Movement below this distance per tick counts as stuck.")
        .defaultValue(0.05)
        .range(0.01, 0.5)
        .sliderRange(0.01, 0.25)
        .build());

    private Vec3 lastPosition;
    private int stuckTicks;
    private int restartTicks;
    private int restartCooldown;
    private int jumpPulseTicks;

    public EFlyUnstuck() {
        super(EFlyAutopilotAddon.ELYTRA, "efly-unfuck", "Restarts Meteor's regular Elytra Fly when movement is stuck.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        if (jumpPulseTicks > 0 && mc.options != null) mc.options.keyJump.setDown(false);
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            reset();
            return;
        }

        ElytraFly efly = Modules.get().get(ElytraFly.class);
        if (restartCooldown > 0) restartCooldown--;
        if (jumpPulseTicks > 0 && --jumpPulseTicks == 0) mc.options.keyJump.setDown(false);

        if (restartTicks > 0) {
            if (--restartTicks == 0) {
                if (!efly.isActive()) efly.toggle();
                mc.options.keyJump.setDown(true);
                jumpPulseTicks = 2;
                restartCooldown = 100;
                lastPosition = mc.player.position();
            }
            return;
        }

        boolean directionHeld = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
        if (!efly.isActive() || !mc.player.isFallFlying() || !directionHeld || restartCooldown > 0) {
            stuckTicks = 0;
            lastPosition = mc.player.position();
            return;
        }

        Vec3 position = mc.player.position();
        if (lastPosition != null && position.distanceTo(lastPosition) < stuckDistance.get()) stuckTicks++;
        else stuckTicks = 0;
        lastPosition = position;

        if (stuckTicks >= Math.max(1, (int) Math.round(stuckSeconds.get() * 20))) {
            efly.toggle();
            mc.options.keyJump.setDown(false);
            restartTicks = 4;
            stuckTicks = 0;
            info("Stuck for %.1f seconds; restarting Elytra Fly.", stuckSeconds.get());
        }
    }

    private void reset() {
        lastPosition = mc.player == null ? null : mc.player.position();
        stuckTicks = 0;
        restartTicks = 0;
        restartCooldown = 0;
        jumpPulseTicks = 0;
    }
}
