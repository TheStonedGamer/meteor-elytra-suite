package com.brianthemint.eflyautopilot;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public class EFlyAutoStop extends Module {
    public enum TargetMode { X, Z, Both }
    public enum ArrivalAction { Stop, Disconnect }

    private final SettingGroup sgTarget = settings.createGroup("Destination");
    private final SettingGroup sgAction = settings.createGroup("Arrival");

    private final Setting<TargetMode> targetMode = sgTarget.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("X or Z stops when that highway coordinate is crossed. Both targets an exact X/Z point.")
        .defaultValue(TargetMode.Both)
        .build());

    private final Setting<Double> targetX = sgTarget.add(new DoubleSetting.Builder()
        .name("target-x")
        .description("Destination X coordinate.")
        .defaultValue(0)
        .range(-30_000_000, 30_000_000)
        .sliderRange(-100_000, 100_000)
        .visible(() -> targetMode.get() != TargetMode.Z)
        .build());

    private final Setting<Double> targetZ = sgTarget.add(new DoubleSetting.Builder()
        .name("target-z")
        .description("Destination Z coordinate.")
        .defaultValue(0)
        .range(-30_000_000, 30_000_000)
        .sliderRange(-100_000, 100_000)
        .visible(() -> targetMode.get() != TargetMode.X)
        .build());

    private final Setting<Double> arrivalRadius = sgTarget.add(new DoubleSetting.Builder()
        .name("arrival-radius")
        .description("How close to the destination counts as arrival. Crossing detection prevents overshooting at high speed.")
        .defaultValue(8)
        .range(1, 128)
        .sliderRange(1, 64)
        .build());

    private final Setting<ArrivalAction> arrivalAction = sgAction.add(new EnumSetting.Builder<ArrivalAction>()
        .name("arrival-action")
        .description("Stops EFly locally or disconnects from the server after stopping.")
        .defaultValue(ArrivalAction.Stop)
        .build());

    private double previousX;
    private double previousZ;
    private boolean hasPrevious;

    public EFlyAutoStop() {
        super(EFlyAutopilotAddon.ELYTRA, "efly-auto-stop", "Stops or logs out at a configured highway coordinate.");
    }

    @Override
    public void onActivate() {
        hasPrevious = mc.player != null;
        if (hasPrevious) {
            previousX = mc.player.getX();
            previousZ = mc.player.getZ();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            hasPrevious = false;
            return;
        }

        double x = mc.player.getX();
        double z = mc.player.getZ();
        if (hasPrevious && arrived(previousX, previousZ, x, z)) {
            stopFlight();
            info("Arrived at highway destination (%.1f, %.1f).", x, z);
            if (arrivalAction.get() == ArrivalAction.Disconnect && mc.getConnection() != null) {
                mc.getConnection().getConnection().disconnect(Component.literal("EFly Auto Stop destination reached"));
            }
            toggle();
            return;
        }

        previousX = x;
        previousZ = z;
        hasPrevious = true;
    }

    private boolean arrived(double oldX, double oldZ, double x, double z) {
        double radius = arrivalRadius.get();
        return switch (targetMode.get()) {
            case X -> Math.abs(x - targetX.get()) <= radius || crossed(oldX, x, targetX.get());
            case Z -> Math.abs(z - targetZ.get()) <= radius || crossed(oldZ, z, targetZ.get());
            case Both -> segmentDistanceSquared(oldX, oldZ, x, z, targetX.get(), targetZ.get()) <= radius * radius;
        };
    }

    private static boolean crossed(double before, double after, double target) {
        return (before - target) * (after - target) <= 0 && before != after;
    }

    private static double segmentDistanceSquared(double ax, double az, double bx, double bz, double px, double pz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0) return sq(px - ax) + sq(pz - az);
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lengthSquared));
        return sq(px - (ax + t * dx)) + sq(pz - (az + t * dz));
    }

    private static double sq(double value) {
        return value * value;
    }

    private void stopFlight() {
        disable(EFlyAutopilot.class);
        disable(VectorElytra.class);
        disable(EFlySpeed.class);
        disable(EFlyUnstuck.class);
        disable(ElytraFly.class);
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    private <T extends Module> void disable(Class<T> type) {
        T module = Modules.get().get(type);
        if (module != null && module.isActive()) module.toggle();
    }
}
