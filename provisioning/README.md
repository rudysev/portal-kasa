# Install portal-kasa on your Portal

No building, no command line, no developer tools. You just need the Portal, a USB‑C
cable, and a computer.

## Steps

1. **On the Portal:** open **Settings → Debug** and turn on **ADB Enabled**.
2. **Connect** the Portal to your computer with a **USB‑C cable**.
3. **Double-click** the installer for your computer:
   - **macOS:** `Install-PortalKasa.command`
   - **Windows:** `Install-PortalKasa.bat`
4. When the Portal screen shows **"Allow USB debugging?"**, tap **Allow** (tick
   "Always allow from this computer").
5. Wait for **"Done."** — then open **Kasa Plugs** on the Portal.

The installer does everything else automatically: it downloads Android's `adb` if you
don't have it, downloads the app, installs it, and opens it. Plugs on the same Wi-Fi are
discovered and listed automatically — no sign-in.

For voice control, enable **"Kasa Plugs"** in the assistant's **Settings → External
tools**, then start a new chat and say e.g. *"hey jarvis, turn on the coffee maker"*.

## To remove it

Double-click **`Uninstall-PortalKasa`** (`.command` on macOS, `.bat` on Windows).

## Notes & troubleshooting

- **Windows "blocked files":** Windows marks files downloaded from the internet as
  blocked. If a script won't run, right-click it → **Properties** → tick **Unblock** →
  **OK**, then try again.
- **macOS "unidentified developer":** if double-clicking is blocked, right-click
  `Install-PortalKasa.command` → **Open** → **Open**.
- **"More than one device is connected":** unplug other Android devices and re-run.
- **Same Wi-Fi only:** the Portal and the plugs must be on the same network.
- **Advanced:** the scripts (`install.sh` / `install.ps1`) also accept `--local`
  (`-Local`) to install a locally built APK, plus `--uninstall` (`-Uninstall`) and
  `--status` (`-Status`). `--local` uses the repo's debug build (`app/build/.../app-debug.apk`),
  or pass a path (`--local <apk>` / `-Apk <path>`); you can also drop an `.apk` into the
  `apks/` folder next to the scripts. Otherwise the latest published release is
  downloaded. Settings live in `config.env`.
