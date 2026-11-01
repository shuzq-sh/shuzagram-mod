# ShuzaGram Mod

An Android Telegram client based on the open-source [Telegram for Android](https://github.com/DrKLO/Telegram) sources, with extra features:

- **Liquid glass UI** — translucent, blurred surfaces across the app.
- **Ghost mode** — hide online/typing/read receipts; optional scheduled-send delay.
- **Kept messages** — deleted messages stay with a mark; edit history is viewable.
- **Couples** — a shared relationship status and days-together counter, by mutual consent.
- **Profile banners**, custom fonts, themes and app icons.
- **Plugin system** compatible with exteraGram `.plugin` files (embedded Python).

Not affiliated with Telegram FZ-LLC or exteraGram. Licensed under **GPL-2.0** (see `LICENSE`).

## Building

1. `git submodule update --init --recursive` to fetch the native dependencies.
2. Get your own `api_id` / `api_hash` from <https://my.telegram.org> and set them in
   `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`.
3. Add your own signing keystore and `google-services.json`.
4. `./gradlew :TMessagesProj_App:assembleAfatRelease`

## Plugins

Install `.plugin` files in Settings → ShuzaGram Mod → Plugins. The plugin API mirrors
exteraGram's (`base_plugin`, `client_utils`, `hook_utils`, `ui.settings`, …).
