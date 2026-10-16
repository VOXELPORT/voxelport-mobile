# VoxelPort for Android

[![License: MIT](https://img.shields.io/badge/License-MIT-5FAE3B?style=flat-square)](LICENSE)
[![Website](https://img.shields.io/badge/Website-voxelport.in-5FAE3B?style=flat-square)](https://voxelport.in)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/2wJbCPwP4r)

Host a Minecraft: Java Edition server **from your phone**. VoxelPort for Android runs a real
[Paper](https://papermc.io) server on the device and puts it online through the
[VoxelPort relay](https://github.com/VOXELPORT/relay), so friends join from anywhere with plain
Minecraft. There is no port forwarding, no account and no PC.

Get the app: **[voxelport.in/download](https://voxelport.in/download)** · Android guide:
[voxelport.in/guides/host-minecraft-server-android-phone](https://voxelport.in/guides/host-minecraft-server-android-phone)

The desktop version for Windows and Linux lives in
[VoxelPort-App](https://github.com/VOXELPORT/VoxelPort-App).

## What it does

- Installs and starts a Paper server (Java runtime included), with a live console, a RAM slider and a
  performance profile (Low / Balanced / High) picked from the phone's RAM and cores.
- Makes it public through the relay: `play.voxelport.in:<port>`, or a free `yourname.voxelport.in`
  address that needs no port.
- Optional Bedrock crossplay (Geyser + Floodgate), and an optional "allow cracked players" switch that
  states its risks before it turns anything on.
- A file manager, a `server.properties` editor and a browser for Modrinth plugins and mods.
- A mobile-data meter with limits and a Data saver mode, because a server on a phone can use a lot.
- A private device key, kept on the phone, that your public port and custom address belong to, with a
  backup and restore screen so you can move to a new phone.
- Updates itself from voxelport.in (the app is sideloaded, so there is no store to do it).

## How it works

The phone is an arm64 Linux machine, so the app ships an OpenJDK 25 runtime and starts it inside its own
`:jvm` process, calling the JVM's launcher through JNI (`app/src/main/cpp/launcher.c`). The tunnel to the
relay is the same Go code the desktop app and the command-line client use, compiled for Android with
`gomobile` (the [`cli`](https://github.com/VOXELPORT/cli) repository). The UI is Kotlin and Jetpack Compose.

| Where                                    | What                                                              |
| ---------------------------------------- | ----------------------------------------------------------------- |
| `app/src/main/java/.../MainActivity.kt`  | The screens and the whole host flow                               |
| `ServerService.kt`, `TunnelService.kt`   | Foreground services: the Java server and the relay tunnel         |
| `Setup.kt`, `Jvm.kt`                     | Unpacking Java, downloading Paper, building the launch command    |
| `Tuning.kt`, `YamlEdit.kt`               | The performance profiles and the config edits they make           |
| `DataLogic.kt`, `DataMeter.kt`           | The mobile-data meter and limits                                  |
| `UpdateLogic.kt`, `Updater.kt`           | The self-updater                                                  |
| `app/src/main/cpp/`                      | The JNI launcher that starts the JVM                              |
| `tools/publish-android.sh`               | How the official build is published (you do not need it)          |

The pure logic (tuning, YAML edits, the data meter, update checks) has JUnit tests in `app/src/test`.

## Building it

See **[BUILDING.md](BUILDING.md)**. Two large files are fetched or built separately and are not in
this repository: the tunnel library (`vpmobile.aar`) and the Java runtime.

## Forking

If you ship your own build, change `applicationId`, the update feed (`MANIFEST_URL` in
`UpdateLogic.kt`) and the support links, so your users are not offered VoxelPort's updates and your
build is clearly yours. The VoxelPort name and logo belong to VoxelPort: please do not use them for an
unofficial fork.

## Third-party software

- The bundled Java runtime is **OpenJDK**, licensed GPLv2 with the Classpath Exception.
- **Paper**, **Geyser/Floodgate**, **Fabric** and plugins from **Modrinth** are downloaded from their
  own servers when you ask for them. They are not part of this repository, and each keeps its own license.
- Running a Minecraft server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA).
  VoxelPort is an independent project, not affiliated with Mojang or Microsoft.

## Community, support and security

- Questions, ideas, showing off your server: the [Discord](https://discord.gg/2wJbCPwP4r).
- VoxelPort is free and has no ads. If it helped, you can [support it](https://voxelport.in/support).
- Found a security problem? Please do not open a public issue. Email `traz@voxelport.in`, or use
  **Security > Report a vulnerability** on GitHub.

## License

[MIT](LICENSE).
