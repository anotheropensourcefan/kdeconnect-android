# Updating this fork from KDE Connect

This branch (`simple-home`) adds an optional simple remote UI on top of upstream KDE Connect. Keep plugin and protocol code untouched so you can pull upstream changes with small merge conflicts.

## Remotes

- `origin` — upstream KDE Connect: `https://github.com/KDE/kdeconnect-android.git`
- `mine` (optional) — your personal fork, for backup pushes only

Do not push to `origin`. Leave `master` tracking KDE with no local edits. All UX work stays on `simple-home`.

## One-time setup

```powershell
git checkout -b simple-home
git remote add mine https://github.com/<you>/kdeconnect-android.git
git push -u mine simple-home
```

Skip the `mine` steps if you do not want a fork backup.

## Pulling upstream updates

```powershell
git checkout simple-home
git fetch origin
git log HEAD..origin/master --oneline
git merge origin/master
```

Then build the app. If it compiles and the device screen still opens, the update is in.

Push to your fork when you want a backup:

```powershell
git push mine simple-home
```

## Why conflicts stay small

- New UI lives under `src/main/java/org/kde/kdeconnect/ui/simple/` and `src/main/res/values/simple_home_strings.xml`. Those files do not exist upstream.
- Upstream translation commits edit `strings.xml` and `values-*`. This fork does not touch those files for the simple UI.
- The usual conflict spots are:
  - `DeviceFragment.refreshUI` (classic vs simple branch) and the overflow menu item
  - the trailing “Switch to classic view” row in `PluginSettingsListFragment`

If a plugin method you call renamed or changed signature, the build fails at the call site in `ui/simple/`. Adjust the call; do not copy protocol code into the simple UI.

## Switching views in the app

- **Classic view:** device overflow (⋮) → Switch to simple view
- **Simple view:** Settings → Switch to classic view (bottom of the plugin list)

The choice is stored in the app-wide preference `simple_view_enabled` (default off).

## App name and package id

- Launcher name is **KDEC** (`app_launcher_name` in `simple_home_strings.xml`).
- Package id is **`org.kde.kdec`** (debug: `org.kde.kdec.debug`), not the official `org.kde.kdeconnect_tp`.
- You can keep the Play Store / F-Droid app and KDEC installed together. Pair KDEC to your computers separately; it is a different app with its own certificate and settings.
- The Gradle `namespace` stays `org.kde.kdeconnect_tp` so source merges with upstream stay small. Only `applicationId` is forked.
- When merging upstream, keep your `applicationId = "org.kde.kdec"` if the merge reverts it.
