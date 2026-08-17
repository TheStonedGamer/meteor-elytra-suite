package com.brianthemint.eflyautopilot;

import baritone.api.BaritoneAPI;
import baritone.api.utils.Rotation;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class EFlyAutopilot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSteering = settings.createGroup("Steering");
    private final SettingGroup sgFlight = settings.createGroup("Takeoff & Ground Skim");

    private final Setting<Boolean> manageElytraFly = sgGeneral.add(new BoolSetting.Builder()
        .name("manage-elytra-fly")
        .description("Enables Meteor Elytra Fly and selects Vanilla mode when this module starts.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoDetect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-detect-highway")
        .description("Detects the highway axis, centerline, and open flight Y.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> detectionRange = sgGeneral.add(new IntSetting.Builder()
        .name("detection-range")
        .description("Distance used to identify the highway corridor.")
        .defaultValue(24).range(8, 48).sliderRange(8, 48)
        .visible(autoDetect::get)
        .build());

    private final Setting<Double> customYaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("custom-yaw")
        .description("Highway direction used when automatic detection is disabled.")
        .defaultValue(0).range(0, 360).sliderRange(0, 360)
        .visible(() -> !autoDetect.get())
        .build());

    private final Setting<Integer> cruiseY = sgGeneral.add(new IntSetting.Builder()
        .name("cruise-y")
        .description("Flight corridor Y. Automatic detection updates this value.")
        .defaultValue(121).range(-64, 320).sliderRange(0, 256)
        .build());

    private final Setting<Double> corridorRadius = sgSteering.add(new DoubleSetting.Builder()
        .name("center-radius")
        .description("Maximum lane offset from highway center.")
        .defaultValue(2).range(0.5, 2).sliderRange(0.5, 2)
        .build());

    private final Setting<Integer> highwayWidth = sgSteering.add(new IntSetting.Builder()
        .name("highway-width")
        .description("Width of the clear highway tunnel in blocks. Controls the safe lateral steering range.")
        .defaultValue(4)
        .range(3, 5)
        .sliderRange(3, 5)
        .build());

    private final Setting<Double> scanDistance = sgSteering.add(new DoubleSetting.Builder()
        .name("obstacle-scan-distance")
        .description("Maximum loaded distance scanned for obstructions.")
        .defaultValue(112).range(16, 192).sliderRange(16, 192)
        .build());

    private final Setting<Double> turnRate = sgSteering.add(new DoubleSetting.Builder()
        .name("maximum-turn-rate")
        .description("Maximum yaw correction per tick.")
        .defaultValue(12).range(2, 30).sliderRange(2, 30)
        .build());

    private final Setting<Double> reactionSeconds = sgSteering.add(new DoubleSetting.Builder()
        .name("reaction-seconds")
        .description("Steering lead based on current speed.")
        .defaultValue(0.5).range(0.15, 1.5).sliderRange(0.15, 1.5)
        .build());

    private final Setting<Boolean> autoTakeoff = sgFlight.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Holds jump and enables Meteor's automatic Vanilla EFly takeoff.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> groundSkim = sgFlight.add(new BoolSetting.Builder()
        .name("ground-skim")
        .description("Maintains a low height above the highway floor and climbs temporarily for an obstruction.")
        .defaultValue(true)
        .build());

    private final Setting<Double> skimHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("skim-height").description("Target player height above the highway floor.")
        .defaultValue(0.35).range(0.05, 2).sliderRange(0.05, 1.5)
        .visible(groundSkim::get).build());

    private final Setting<Double> climbHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("obstacle-climb-height").description("Temporary extra height requested when all lanes ahead are blocked.")
        .defaultValue(2).range(0.5, 6).sliderRange(0.5, 4)
        .visible(groundSkim::get).build());

    private final Setting<Double> climbReactionSeconds = sgFlight.add(new DoubleSetting.Builder()
        .name("climb-reaction-seconds").description("Starts climbing when the best lane has fewer than this many seconds of clearance.")
        .defaultValue(1.5).range(0.25, 4).sliderRange(0.25, 3)
        .visible(groundSkim::get).build());

    private final Setting<Double> heightTolerance = sgFlight.add(new DoubleSetting.Builder()
        .name("height-tolerance").description("Allowed deviation before vertical correction.")
        .defaultValue(0.25).range(0.05, 1).sliderRange(0.05, 0.75)
        .visible(groundSkim::get).build());

    private final Setting<Double> verticalCorrectionSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("vertical-correction-speed").description("Temporary Meteor EFly vertical speed used for smooth height corrections.")
        .defaultValue(0.15).range(0.02, 0.5).sliderRange(0.02, 0.35)
        .visible(groundSkim::get).build());

    private Vec3 centerOrigin;
    private double highwayYaw;
    private boolean enabledElytraFly;
    private boolean originalAutoTakeoff;
    private double originalVerticalSpeed;
    private double bestLaneClearance;
    private double bestLaneOffset;
    private boolean bestLaneBlocked;
    private double detectedUsableRadius = 0.65;
    private int detectedTunnelHeight = 3;

    public EFlyAutopilot() {
        super(EFlyAutopilotAddon.ELYTRA, "efly-autopilot", "Keeps Vanilla Elytra Fly centered on a Nether highway and steers around objects without fireworks or landing control.");
    }

    @Override
    public void onActivate() {
        ElytraFly efly = Modules.get().get(ElytraFly.class);
        originalAutoTakeoff = efly.autoTakeOff.get();
        originalVerticalSpeed = efly.verticalSpeed.get();
        enabledElytraFly = false;
        if (manageElytraFly.get()) {
            efly.flightMode.set(ElytraFlightModes.Vanilla);
            if (autoTakeoff.get()) efly.autoTakeOff.set(true);
            if (groundSkim.get()) efly.verticalSpeed.set(verticalCorrectionSpeed.get());
            if (!efly.isActive()) {
                efly.toggle();
                enabledElytraFly = true;
            }
        }

        detectHighway();
    }

    @Override
    public void onDeactivate() {
        Modules.get().get(VectorElytra.class).clearAutopilotVector();
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        ElytraFly efly = Modules.get().get(ElytraFly.class);
        efly.autoTakeOff.set(originalAutoTakeoff);
        efly.verticalSpeed.set(originalVerticalSpeed);
        if (enabledElytraFly && efly.isActive() && !Modules.get().get(VectorElytra.class).isActive()) efly.toggle();
        centerOrigin = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (!mc.player.isFallFlying()) {
            if (autoTakeoff.get()) mc.options.keyJump.setDown(true);
            return;
        }
        if (centerOrigin == null) detectHighway();

        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        BaritoneAPI.getProvider().getPrimaryBaritone().getWorldProvider().ifWorldLoaded(world ->
            world.getCachedWorld().queueForPacking(mc.level.getChunkAt(mc.player.blockPosition())));
        steer();
        updateGroundSkim();
    }

    private void detectHighway() {
        if (mc.player == null || mc.level == null) return;
        if (!autoDetect.get()) {
            highwayYaw = customYaw.get();
            centerOrigin = mc.player.position();
            return;
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        bestLaneClearance = 0;
        Vec3 bestCenter = mc.player.position();
        double bestYaw = snapYaw(mc.player.getYRot());
        int bestY = mc.player.blockPosition().getY();

        for (int yOffset = -3; yOffset <= 3; yOffset++) {
            int y = mc.player.blockPosition().getY() + yOffset;
            for (int directionIndex = 0; directionIndex < 8; directionIndex++) {
                double yaw = directionIndex * 45.0;
                Vec3 forward = Vec3.directionFromRotation(0, (float) yaw);
                Vec3 side = new Vec3(-forward.z, 0, forward.x);
                for (int centerOffset = -4; centerOffset <= 4; centerOffset++) {
                    Vec3 candidateCenter = mc.player.position().add(side.scale(centerOffset));
                    double score = -Math.abs(Mth.wrapDegrees(yaw - mc.player.getYRot())) * 0.08 - Math.abs(centerOffset) * 0.25;
                    for (int distance = 3; distance <= detectionRange.get(); distance += 3) {
                        Vec3 rowCenter = candidateCenter.add(forward.scale(distance));
                        for (int lateral = -2; lateral <= 2; lateral++) {
                            BlockPos feet = blockPos(rowCenter.add(side.scale(lateral)), y);
                            boolean floor = collides(feet.below());
                            boolean clear = !collides(feet) && !collides(feet.above());
                            score += floor ? 1 : -0.5;
                            score += clear ? 2 : -4;
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestCenter = candidateCenter;
                        bestYaw = yaw;
                        bestY = y;
                    }
                }
            }
        }

        centerOrigin = bestCenter;
        highwayYaw = bestYaw;
        cruiseY.set(bestY);
        measureTunnel(bestCenter, bestYaw, bestY);
    }

    private void steer() {
        Vec3 forward = Vec3.directionFromRotation(0, (float) highwayYaw);
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        Vec3 fromCenter = mc.player.position().subtract(centerOrigin);
        Vec3 centerHere = centerOrigin.add(forward.scale(fromCenter.dot(forward)));
        double loadedScan = loadedDistance(forward, scanDistance.get());
        double widthRadius = (highwayWidth.get() - 1) * 0.5 - 0.35;
        double radius = Math.min(corridorRadius.get(), widthRadius);
        double bestOffset = 0;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int lane = -2; lane <= 2; lane++) {
            double offset = lane * radius / 2.0;
            double clear = 0;
            boolean blocked = false;
            for (double distance = 4; distance <= loadedScan; distance += 4) {
                BlockPos feet = blockPos(centerHere.add(forward.scale(distance)).add(side.scale(offset)), cruiseY.get());
                if (collides(feet) || collides(feet.above())) {
                    blocked = true;
                    break;
                }
                clear = distance;
            }
            double score = clear - Math.abs(offset) * 2;
            if (score > bestScore) {
                bestScore = score;
                bestOffset = offset;
                bestLaneOffset = offset;
                bestLaneClearance = clear;
                bestLaneBlocked = blocked;
            }
        }

        double speedBps = mc.player.getDeltaMovement().multiply(1, 0, 1).length() * 20;
        double lead = Mth.clamp(speedBps * reactionSeconds.get(), 12, 48);
        Vec3 target = centerHere.add(forward.scale(lead)).add(side.scale(bestOffset));
        Vec3 delta = target.subtract(mc.player.position());
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float correction = Mth.clamp(Mth.wrapDegrees(desiredYaw - mc.player.getYRot()), -turnRate.get().floatValue(), turnRate.get().floatValue());
        float controlledYaw = mc.player.getYRot() + correction;
        VectorElytra vectorElytra = Modules.get().get(VectorElytra.class);
        if (vectorElytra.isActive()) {
            vectorElytra.setAutopilotVector(new Vec3(delta.x, 0, delta.z).normalize());
        } else {
            BaritoneAPI.getProvider().getPrimaryBaritone().getLookBehavior().updateTarget(new Rotation(controlledYaw, mc.player.getXRot()), true);
            mc.player.setYRot(controlledYaw);
        }
    }

    private void updateGroundSkim() {
        if (!groundSkim.get()) return;
        int floorY = findFloorY();
        if (floorY == Integer.MIN_VALUE) return;

        double speedBps = mc.player.getDeltaMovement().multiply(1, 0, 1).length() * 20;
        double reactionDistance = Math.max(12, speedBps * climbReactionSeconds.get());
        boolean obstacleClose = bestLaneBlocked && bestLaneClearance < reactionDistance;
        boolean canClimb = obstacleClose && detectedTunnelHeight >= 4
            && hasElevatedClearance(Math.min(reactionDistance, scanDistance.get()));
        double targetY = floorY + skimHeight.get() + (canClimb ? climbHeight.get() : 0);
        int ceilingY = findCeilingY();
        if (ceilingY != Integer.MAX_VALUE) targetY = Math.min(targetY, ceilingY - 0.8);
        double error = targetY - mc.player.getY();

        if (ceilingY != Integer.MAX_VALUE && ceilingY - mc.player.getY() < 0.75) mc.options.keyShift.setDown(true);
        else if (error > heightTolerance.get()) mc.options.keyJump.setDown(true);
        else if (error < -heightTolerance.get()) mc.options.keyShift.setDown(true);
    }

    private boolean hasElevatedClearance(double distanceToCheck) {
        Vec3 forward = Vec3.directionFromRotation(0, (float) highwayYaw);
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        Vec3 fromCenter = mc.player.position().subtract(centerOrigin);
        Vec3 centerHere = centerOrigin.add(forward.scale(fromCenter.dot(forward)));
        int elevatedY = Mth.floor(mc.player.getY() + climbHeight.get());

        for (double distance = 0; distance <= distanceToCheck; distance += 2) {
            BlockPos feet = blockPos(centerHere.add(forward.scale(distance)).add(side.scale(bestLaneOffset)), elevatedY);
            if (collides(feet) || collides(feet.above())) return false;
        }
        return true;
    }

    private int findCeilingY() {
        BlockPos playerPos = mc.player.blockPosition();
        for (int y = playerPos.getY() + 1; y <= playerPos.getY() + 8; y++) {
            BlockPos pos = new BlockPos(playerPos.getX(), y, playerPos.getZ());
            if (collides(pos)) return y;
        }
        return Integer.MAX_VALUE;
    }

    private void measureTunnel(Vec3 center, double yaw, int floorAirY) {
        Vec3 forward = Vec3.directionFromRotation(0, (float) yaw);
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        double clearSideOffset = 0.5;

        for (double offset = 0.5; offset <= 2.5; offset += 0.5) {
            boolean bothSidesClear = true;
            for (int distance = 0; distance <= 12; distance += 4) {
                for (int sign : new int[] {-1, 1}) {
                    BlockPos feet = blockPos(center.add(forward.scale(distance)).add(side.scale(offset * sign)), floorAirY);
                    if (collides(feet) || collides(feet.above())) {
                        bothSidesClear = false;
                        break;
                    }
                }
                if (!bothSidesClear) break;
            }
            if (!bothSidesClear) break;
            clearSideOffset = offset;
        }

        detectedUsableRadius = Mth.clamp(clearSideOffset - 0.35, 0.5, 2.0);
        detectedTunnelHeight = 0;
        BlockPos centerFeet = blockPos(center, floorAirY);
        for (int height = 0; height < 8; height++) {
            if (collides(centerFeet.above(height))) break;
            detectedTunnelHeight++;
        }
    }

    private int findFloorY() {
        BlockPos playerPos = mc.player.blockPosition();
        for (int y = playerPos.getY(); y >= playerPos.getY() - 10; y--) {
            BlockPos pos = new BlockPos(playerPos.getX(), y, playerPos.getZ());
            if (collides(pos)) return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    private double loadedDistance(Vec3 forward, double requested) {
        double loaded = 16;
        for (double distance = 32; distance <= requested; distance += 16) {
            Vec3 sample = mc.player.position().add(forward.scale(distance));
            if (!mc.level.getChunkSource().hasChunk(Mth.floor(sample.x) >> 4, Mth.floor(sample.z) >> 4)) break;
            loaded = distance;
        }
        return loaded;
    }

    private boolean collides(BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private static BlockPos blockPos(Vec3 pos, int y) {
        return new BlockPos(Mth.floor(pos.x), y, Mth.floor(pos.z));
    }

    private static double snapYaw(double yaw) {
        return (Math.round(yaw / 45.0) * 45.0 + 360.0) % 360.0;
    }

    @Override
    public String getInfoString() {
        String mode = Modules.get().get(VectorElytra.class).isActive() ? "Vector" : "Yaw";
        return mode;
    }
}
