## 1. Implementation
- [x] 1.1 Review FileShareScreen link-share allow/reject handling and selection state to mirror in tray actions.
- [x] 1.2 Add link-share defaults (selected files + hidden-file toggle) and keep them in sync when the share page bottom sheet is confirmed.
- [x] 1.3 Update FileShareScreen waiting-device allow to auto-authorize using the synced defaults without opening the bottom sheet.
- [x] 1.4 Update the tray Easy Share entry to reflect running vs not-running state.
- [x] 1.5 When running, add tray submenu actions: open page, address list with per-address open-in-browser and copy-to-clipboard actions.
- [x] 1.6 Add tray authorization restriction toggles that mirror mutual exclusivity between auto-approve and auto-authorize-same-device.
- [x] 1.7 Add tray device authorization submenus (pending/authorized/rejected) with counts and actions aligned to the synced defaults.
- [x] 1.8 Add a tray action to stop the Easy Share service while running.
- [x] 1.9 Add a confirmation submenu for stopping the Easy Share service.

## 2. Validation
- [x] 2.1 Desktop: verify the tray label updates when the link-share server starts/stops.
- [x] 2.2 Desktop: verify open-in-browser and copy-to-clipboard for each address.
- [x] 2.3 Desktop: verify pending/authorized/rejected device actions match FileShareScreen outcomes without opening the bottom sheet.
- [x] 2.4 Desktop: verify the tray stop service action shuts down the link-share service and clears device lists.
- [x] 2.5 Desktop: verify the tray stop service confirmation is required before stopping.
