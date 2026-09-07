# OPL Mobile SMB

Android app that turns the phone itself into an SMBv1 game server for **Open PS2 Loader (OPL)**.

## What it does

- Creates an app-managed `PS2SMB/DVD` and `PS2SMB/CD` library on the phone.
- Lets you pick a local `.iso` with Android's file picker.
- Streams/copies the ISO into the selected OPL folder without loading the whole image into RAM.
- Runs an SMBv1 server directly on the phone.
- Uses TCP port **4450**, so root is not required.
- Exposes the share as **PS2SMB** with guest access.
- Keeps a foreground service, CPU wake lock and Wi-Fi lock while the server is active.
- Gerencia e desinstala ISOs e jogos USBUtil pelo app, mostrando o tamanho de cada jogo e o espaço ocupado/livre do armazenamento.
- Selects internal storage or a USB/SD volume as the `PS2SMB` root.
- Reads existing USBUtil/USBExtreme `ul.cfg` + `ul.*` installations.
- Installs an ISO directly as USBUtil, splitting it into 1 GiB chunks and updating `ul.cfg` only after a successful copy.

## OPL configuration

Connect the PS2 and Android device to the same local network. Start the server in the app and note the phone IP.

In OPL network/SMB settings use:

```text
Server / IP: <phone IP shown in app>
Port:        4450
Share:       PS2SMB
User:        GUEST
Password:    (empty)
```

Enable/start ETH/network games and refresh the game list.

The OPL project currently documents SMBv1 shares and direct ISO support in `DVD/` and `CD/`.

## Build

Open this folder with a recent Android Studio.

Requirements:

- Android SDK 36 installed
- JDK 17+
- Internet access during the first Gradle sync (dependencies are fetched from Google/Maven Central/JitPack)

Then build the `debug` APK from Android Studio, or with an installed Gradle:

```bash
./build-apk.sh
```

A GitHub Actions workflow is also included at `.github/workflows/android.yml`; after pushing the project to GitHub, run **Build Android APK** and download the generated artifact.

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Technical notes

This proof-of-concept uses the Android-compatible JFileServer fork published under the `com.github.buttercookie42:jfileserver` JitPack coordinate. The server is configured for native SMB-over-TCP on port 4450 and guest access.

The SMB approach and Android-specific JFileServer compatibility choices were informed by the open-source SimbaDroid project. SimbaDroid is MPL-2.0 licensed; this project does not bundle SimbaDroid itself.

JFileServer is LGPL-3.0 licensed. See the upstream projects for their complete license terms.

## Security

SMBv1 is an obsolete protocol. This app is intentionally designed for a trusted home LAN and OPL compatibility. Do not port-forward TCP 4450 or expose the server to the public Internet.

## Current status

Source build for direct Android-to-OPL SMBv1 serving. The app keeps the Android-specific NetBIOS ports disabled/remapped and uses native SMB-over-TCP on port 4450. Test USBUtil installs with a copy of your data before relying on it as the only copy of a game image.


## Armazenamento removível e USBUtil

- A tela **Selecionar armazenamento / pendrive** lista os volumes locais detectados pelo Android.
- Quando um volume é escolhido, a raiz dele vira a raiz do share `PS2SMB`.
- ISOs continuam em `DVD/` e `CD/`.
- USBExtreme/USBUtil é reconhecido diretamente pela combinação `ul.cfg` + arquivos `ul.*` na raiz.
- No Android 11+, servir a raiz de um volume via JFileServer/NIO requer conceder **Acesso a todos os arquivos** ao app.
- O botão **Instalar como USBUtil** recebe uma ISO, nome, Game ID e tipo CD/DVD.
- O instalador gera `ul.<CRC32>.<GAME_ID>.00`, `.01`, ... em partes de até 1 GiB, seguindo o `iso2opl` do OPL.
- `ul.cfg` só é atualizado no fim; se a cópia falhar antes, as partes criadas pela tentativa são removidas.
- A atualização de `ul.cfg` é feita por arquivo temporário + troca, preservando entradas existentes.
- A tela **Gerenciar jogos** mostra formato, tamanho, Game ID/partes e permite desinstalar com confirmação. Para USBUtil, os chunks são preparados por rename e o `ul.cfg` é reescrito atomicamente; em caso de falha antes da troca, os nomes originais são restaurados.


## 0.4.1 - correção de armazenamento removível

- A seleção de USB/SD agora persiste o UUID do volume em vez de depender apenas de `/storage/XXXX-XXXX`.
- Apenas volumes montados em modo leitura/escrita aparecem como destino.
- Antes de aceitar um pendrive, o app faz um teste real de leitura/escrita e valida o espaço reportado.
- O servidor SMB não inicia quando a raiz está desmontada/stale; a interface mostra o motivo em vez de `0 B / ocupação indisponível`.
- O caminho do volume é resolvido novamente quando o Android remonta o dispositivo.
