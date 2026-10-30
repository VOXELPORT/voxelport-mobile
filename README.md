# VoxelPort for Android

[![License: MIT](https://img.shields.io/badge/License-MIT-5FAE3B?style=flat-square)](LICENSE)
[![Website](https://img.shields.io/badge/Website-voxelport.in-5FAE3B?style=flat-square)](https://voxelport.in)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/JAUfmey2fP)

Host a Minecraft: Java Edition server **from your phone**. VoxelPort for Android runs a real
[Paper](https://papermc.io) server on the device and puts it online through the
[VoxelPort relay](https://github.com/VOXELPORT/relay), so friends join from anywhere with plain
Minecraft. There is no port forwarding and no PC to leave on. Friends need no account; you sign in with Discord once.

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
- Sign in with Discord once, the same rule as the desktop app: you join the VoxelPort Discord server, and VoxelPort never sees your Discord password or email.
- A private device key, kept on the phone, that your public port and custom address belong to. You can copy it as a code, or back it up to your Discord account locked with a passphrase only you know (VoxelPort cannot read it), then restore it on a new phone or in the desktop app.
- A Delete server button that removes the server's world, plugins and settings; your backups and address are kept.
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
| `AppAccount.kt`, `GateLogic.kt`, `AccountLogic.kt` | Discord sign-in and the rule for who may host publicly  |
| `IdentityBackup.kt`, `BackupUi.kt`       | The passphrase-locked address backup (same format as the desktop app) |
| `ServerDelete.kt`                        | Deleting the server safely                                        |
| `app/src/main/cpp/`                      | The JNI launcher that starts the JVM                              |
| `tools/publish-android.sh`               | How the official build is published (you do not need it)          |

The pure logic (tuning, YAML edits, the data meter, update checks, the Discord gate, the address backup
crypto) has JUnit tests in `app/src/test`. The backup test opens a blob made by the desktop app's code, so
the two stay compatible.

## Updating from an older build

Builds up to 13 were signed with a key that was lost, so Android cannot update them to build 14 or later.
They need a one-time reinstall: copy your address code first (**Back up or restore your address**), uninstall,
install the new build, then restore the code. Uninstalling deletes the server files stored inside the app, so
copy out anything you want to keep. Builds from 14 on update in place.

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

- Questions, ideas, showing off your server: the [Discord](https://discord.gg/JAUfmey2fP).
- VoxelPort is free and has no ads. If it helped, you can [support it](https://voxelport.in/support).
- Found a security problem? Please do not open a public issue. Email `traz@voxelport.in`, or use
  **Security > Report a vulnerability** on GitHub.

## License

[MIT](LICENSE).
