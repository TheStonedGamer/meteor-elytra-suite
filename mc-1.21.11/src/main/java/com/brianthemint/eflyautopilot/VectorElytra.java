package com.brianthemint.eflyautopilot;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public class VectorElytra extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> manageElytraFly = sgGeneral.add(new BoolSetting.Builder()
        .name("manage-elytra-fly")
        .description("Enables Meteor Elytra Fly in Vanilla mode when Vector Elytra starts.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> stopWithoutInput = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-without-input")
        .description("Stops horizontal movement when no direction key is held. When disabled, Meteor EFly handles coasting.")
        .defaultValue(false)
        .build());

    private Vec3 vector = Vec3.ZERO;
    private boolean hasAutopilotVector;
    private boolean enabledElytraFly;

    public VectorElytra() {
        super(EFlyAutopilotAddon.ELYTRA, "vector-elytra", "Separately controls Meteor Vanilla EFly's horizontal movement vector without locking your camera.");
    }

    @Override
    public void onActivate() {
        ElytraFly efly = Modules.get().get(ElytraFly.class);
        enabledElytraFly = false;
        if (manageElytraFly.get()) {
            efly.flightMode.set(ElytraFlightModes.Vanilla);
            if (!efly.isActive()) {
                efly.toggle();
                enabledElytraFly = true;
            }
        }

        vector = Vec3.ZERO;
        hasAutopilotVector = false;
    }

    @Override
    public void onDeactivate() {
        clearAutopilotVector();
        if (enabledElytraFly && !Modules.get().get(EFlyAutopilot.class).isActive()) {
            ElytraFly efly = Modules.get().get(ElytraFly.class);
            if (efly.isActive()) efly.toggle();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (event.type != MoverType.SELF || mc.player == null || !mc.player.isFallFlying()) return;
        if (!hasAutopilotVector) vector = inputVector();
        if (vector.lengthSqr() < 1.0e-6) {
            if (stopWithoutInput.get()) ((IVec3d) event.movement).meteor$set(0, event.movement.y, 0);
            return;
        }

        double speed = Modules.get().get(ElytraFly.class).horizontalSpeed.get();
        double y = hasAutopilotVector ? event.movement.y : vector.y * speed;
        ((IVec3d) event.movement).meteor$set(vector.x * speed, y, vector.z * speed);
    }

    public void setAutopilotVector(Vec3 vector) {
        if (!isActive() || vector.lengthSqr() < 1.0e-6) return;
        this.vector = vector.multiply(1, 0, 1).normalize();
        hasAutopilotVector = true;
    }

    public void clearAutopilotVector() {
        hasAutopilotVector = false;
        vector = Vec3.ZERO;
    }

    private Vec3 inputVector() {
        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        if (forward == 0 && strafe == 0) return Vec3.ZERO;

        double yaw = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(mc.player.getXRot());
        double cosPitch = Math.cos(pitch);

        // Forward/back follows the camera's full 3D look vector. Strafing stays
        // horizontal so looking up or down does not make A/D gain altitude.
        double x = -Math.sin(yaw) * cosPitch * forward - Math.cos(yaw) * strafe;
        double y = -Math.sin(pitch) * forward;
        double z = Math.cos(yaw) * cosPitch * forward - Math.sin(yaw) * strafe;
        return new Vec3(x, y, z).normalize();
    }

    @Override
    public String getInfoString() {
        return hasAutopilotVector ? "Autopilot" : "WASD";
    }
}
