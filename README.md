# Meteor Elytra Suite

Meteor Client addon providing high-speed Elytra controls and Nether-highway automation.

## Modules

- **EFly Autopilot** — detects a Nether highway, centers flight, and steers around obstacles.
- **Vector Elytra** — camera-relative 3D WASD control, including pitch-based ascent and descent.
- **EFly Speed** — independent manual and adaptive control of Meteor EFly speed.
- **EFly Unfuck** — cycles regular Meteor EFly after movement remains stuck for a configurable time.
- **EFly Auto Stop** — stops or disconnects at a configured X, Z, or X/Z highway destination.
- **Logout** — bind-only immediate server disconnect.

All modules appear in Meteor's dedicated **Elytra** category.

## Supported versions

| Folder | Minecraft | Java | Fabric Loader |
|---|---:|---:|---:|
| `mc-26.2` | 26.2 | 25 | 0.19.3+ |
| `mc-1.21.11` | 1.21.11 | 21 | 0.18.2+ |

Prebuilt JARs are attached to each GitHub Release.

## Building

Each version is an independent Gradle project. Place the matching named/development Meteor Client JAR at `libs/meteor-client.jar`, then run:

```powershell
./gradlew.bat build --no-daemon
```

The 26.2 project also resolves `baritone-meteor:26.2-SNAPSHOT`; the 1.21.11 project resolves `baritone:1.21.10-SNAPSHOT` and remaps Meteor's 1.21.11 production JAR through Loom.

Use only on servers where the relevant client modifications are permitted.

