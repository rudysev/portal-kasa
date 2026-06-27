# Disclaimer

**portal-kasa is an independent, community-built project. It is not affiliated
with, authorized by, endorsed by, or sponsored by Meta Platforms, Inc. or
TP-Link Corporation Limited.**

"Meta", "Meta Portal", and "Portal" are trademarks of Meta Platforms, Inc.
"TP-Link" and "Kasa" are trademarks of TP-Link Corporation Limited. They are
used here only to identify the hardware this app runs on and the smart plugs it
controls (nominative use). portal-kasa is not a Meta or TP-Link product and
ships no Meta or TP-Link code.

## Use at your own risk

portal-kasa is an app you build and install yourself onto a Meta Portal device.
Meta Portal devices are discontinued and receive no official support. By
building, installing, and running this app you accept that:

- Installing and running third-party apps on a device may **void any remaining
  warranty** or violate the device's terms of use.
- Modifying a device or sideloading software always carries some risk. We are
  not aware of this app causing any harm, but **no outcome is guaranteed**.
- The app controls TP-Link Kasa smart plugs over your local network. Toggling a
  plug switches mains power to whatever is plugged into it. **You are responsible
  for what you connect and switch.**

The software is provided "AS IS", without warranty of any kind, under the terms
of the [MIT License](LICENSE). To the maximum extent permitted by law, the
authors and contributors accept no liability for any damage, data loss, or other
harm arising from its use.

## Privacy

portal-kasa has no analytics and no accounts. It stores no credentials — local
control needs none. All communication is **local LAN only**: the app discovers
plugs by UDP broadcast and flips them over TCP on port 9999, talking directly to
plugs on your own network. It contacts no TP-Link cloud, no servers, and no
third party. The `DebugLog` helper writes a best-effort local log file
(`files/debug.txt`) on the device only. No personal data is collected by the
project.

## Reporting issues

If you believe any content here infringes your rights, or you represent Meta or
TP-Link and have concerns, please open an issue or contact the maintainers; we
will respond promptly.
