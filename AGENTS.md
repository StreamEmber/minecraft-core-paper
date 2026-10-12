# StreamEmber Core (Paper) — agent guide

Paper plugin `StreamEmberCore` (`streamember-core.jar`). Mods live in their own repos (`minecraft-<name>-paper`,
started from `minecraft-mod-template`) and compile against this jar (`compileOnly`).

- `src/main/java/com/streamember/minecraft/api/` — **public mod API** (`StreamEmberModule`, `ActionRegistry`,
  `GameAction`, `GameSession`, `JsonValues`, `ModuleManifest`, `StreamEmber`). Changing or removing anything here is a
  breaking change: bump the major version and update the template.
- `src/main/java/com/streamember/minecraft/core/` — internals: identity (config → launcher `:47880/api/customer` → env),
  presence, Falcon settings, EventFabric WebSocket, `/se` commands, idle shutdown. Mods never touch these.
- Single-project Gradle; `.\build.ps1` builds and copies to `MINECRAFT_SERVER_PLUGINS_PATH`.
- Release: bump `version` in `gradle.properties` + `## <version>` section in `CHANGELOG.md`, push to `main`;
  CI publishes `v<version>` with `streamember-core.jar` and `.sha256`. No caches or artifacts in Actions.
- No external dependencies: Java 21 `HttpClient`, Paper's Gson, Adventure components.
- Code, logs and command output in English; docs may be Turkish. Do not build or run tests unless asked.
