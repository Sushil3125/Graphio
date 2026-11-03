# Publishing Code Map to JetBrains Marketplace

These steps use the Gradle build in this directory (IntelliJ Platform Gradle Plugin 2.19.0, Gradle 9.8.2).
Run all commands from `pycharm-plugin/` with `JAVA_HOME` pointing at a JDK 21. On this machine that is the PyCharm
JBR: `/home/sushil/.cache/JetBrains/RemoteDev/dist/c4a9ef17c7e8b_pycharm-253.29346.46/jbr`.

References:
<https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html>,
<https://plugins.jetbrains.com/docs/intellij/plugin-signing.html>,
<https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html>.

## 0. Decisions to make before the first upload (they can't be changed afterwards)

1. **Plugin id** (`<id>` in `src/main/resources/META-INF/plugin.xml`). The id is permanent once uploaded.
   Decided: `com.sushil.codemap` (no JetBrains product words, so the verifier's `TemplateWordInPluginId` check
   passes; nothing is muted). The Java package, action ids, configurable id and settings keys use the same prefix.
2. **Vendor** (`<vendor>Sushil</vendor>`, decided; no url/email attributes). If you add a `url`/`email` later they
   must be real and reachable. On Marketplace the vendor is either your personal
   profile or an organization you create; decide this before uploading, because transferring a plugin later goes
   through JetBrains support.
3. **Name.** "Code Map" (30 chars max). It must not contain "Plugin", "IntelliJ", "JetBrains", or a product name
   such as "PyCharm".
4. **License / EULA.** Marketplace requires one. The plugin bundles ELK.js (EPL-2.0, `codemap/web/vendor/LICENSE.md`),
   so pick a compatible license for codemap. If it's open source, publish the sources and keep the repository URL
   handy for the listing.
5. **Description and change notes** in `plugin.xml`: English first. Since 0.5 the plugin needs no Python
   interpreter and starts no process: the graph is built from PyCharm's Python code model and served by the IDE's
   built-in server (localhost only); say so in the listing. The plugin icon is
   `META-INF/pluginIcon.svg` (+ `_dark`): 40×40 SVG, and it must not resemble a JetBrains logo.
6. **Version.** Bump `<version>` in `plugin.xml` for every upload, because Marketplace rejects a version that already
   exists. Gradle reads the version from there.

## 1. Account

1. Sign in or create a JetBrains Account at <https://plugins.jetbrains.com> (top-right "Sign In").
2. Accept the **JetBrains Marketplace Developer Agreement** when prompted. Fill in the profile and the EEA
   trader / non-trader declaration.
3. Optional: create an **organization** (profile menu, then Organizations) if the vendor should be a team or
   company rather than you personally.

## 2. Build and check the release ZIP

```bash
./gradlew clean buildPlugin verifyPlugin
```

* Output: `build/distributions/codemap-pycharm-<version>.zip`. It contains `codemap-pycharm/lib/codemap-pycharm-<version>.jar`
  with the classes, `plugin.xml`, and the web UI under `codemap-web/` (index.html, app.js, style.css, vendor/elk).
  No Python code is bundled.
* `./gradlew test` runs the headless IDE tests (engine, analyzer behaviour, web layer incl. SSE) and should be green.
* `verifyPlugin` runs the JetBrains Plugin Verifier against the IDE in `-PideLocalPath` (default: the local Remote Dev
  PyCharm 253). To check against a downloaded PyCharm 2025.3 instead, add `-PideLocalPath=`. It must report
  **Compatible**; Marketplace runs the same verifier on upload.
* Smoke test the ZIP by hand: in PyCharm go to Settings | Plugins | ⚙ | Install Plugin from Disk…, choose the ZIP,
  restart, open a Python project, and press Ctrl+Alt+Shift+K: the tab shows the indexing/analysis progress, then
  the map; Ctrl+click a node opens the code. Or run `./gradlew runIde` for a sandbox IDE.

## 3. Signing

Signing is required. `signPlugin` runs automatically before `publishPlugin`, and you can also run it by hand to
sign the ZIP for the manual upload.

1. Create a key and a self-signed certificate once, and keep them secret (never commit them):
   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem          # asks for the passphrase
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```
2. Export the contents (not the paths) as environment variables. `build.gradle.kts` reads exactly these names:
   ```bash
   export CERTIFICATE_CHAIN="$(cat chain.crt)"
   export PRIVATE_KEY="$(cat private.pem)"
   export PRIVATE_KEY_PASSWORD='<passphrase>'
   ```
3. Run `./gradlew signPlugin`. The signed ZIP is written to `build/distributions/codemap-pycharm-<version>-signed.zip`.
   Without these variables `signPlugin` is skipped and `buildPlugin` still produces the unsigned ZIP.

## 4. First upload (must be manual)

The first publication can't be done with Gradle.

1. Go to <https://plugins.jetbrains.com/plugin/add> (or profile, then "Upload plugin").
2. Upload `build/distributions/codemap-pycharm-<version>-signed.zip`. The unsigned ZIP is accepted too, but a
   signed one is preferred.
3. Choose the vendor (you or the organization), the license, and tags (e.g. "Code tools", "Navigation",
   "Python"). Add the source code URL if the plugin is open source.
4. Submit. Automated checks run first (Plugin Verifier, descriptor rules), then a **manual review** by JetBrains.
   Expect a few working days; if there's no response after 3–4 working days, email marketplace@jetbrains.com.
   You'll get an email when the status changes or if reviewers have questions. Every later update is reviewed too,
   usually faster.
5. After approval, finish the plugin page: screenshots of the Code Map tab, documentation link, and issue tracker.

## 5. Updates with `publishPlugin`

1. On Marketplace go to your profile, then **My Tokens**, then **Generate Token**. Copy the token: it is shown only
   once.
2. Bump `<version>` (and `<change-notes>`) in `plugin.xml`.
3. Publish:
   ```bash
   export PUBLISH_TOKEN='perm:…'        # or -PintellijPlatformPublishingToken=… / ORG_GRADLE_PROJECT_intellijPlatformPublishingToken
   export CERTIFICATE_CHAIN=… PRIVATE_KEY=… PRIVATE_KEY_PASSWORD=…
   ./gradlew clean verifyPlugin publishPlugin
   ```
   `publishPlugin` depends on `buildPlugin` and `signPlugin`, and uploads the signed ZIP for the plugin whose id
   is in `plugin.xml`.

## 6. Channels

* By default updates go to the **stable** (`default`) channel.
* To publish a pre-release instead, set `PUBLISH_CHANNEL`, for example
  `PUBLISH_CHANNEL=eap ./gradlew publishPlugin`. Common names are `alpha`, `beta`, and `eap`.
* A custom channel is a separate repository. Testers add `https://plugins.jetbrains.com/plugins/<channel>/list`
  under Settings | Plugins | ⚙ | Manage Plugin Repositories… to receive those builds.

## 7. Compatibility range

`since-build` is 253 (PyCharm 2025.3) and there is no `until-build`, so newer IDEs install the plugin
automatically. Marketplace checks new IDE releases with the verifier and emails you about problems. Check
locally against a new IDE with `./gradlew verifyPlugin -PideLocalPath=<new IDE dir>`. Raise `pluginSinceBuild`
(in `gradle.properties`) only when you start using newer APIs.

## Checklist

- [x] Final `<id>` `com.sushil.codemap`, vendor `Sushil`; `verifyPlugin` passes with nothing muted
- [ ] Final name, description, change notes, icon
- [ ] License chosen and compatible with EPL-2.0 (ELK.js)
- [ ] `<version>` bumped
- [ ] `./gradlew clean buildPlugin test verifyPlugin` is green, and the ZIP was tried with "Install Plugin from Disk"
- [ ] `signPlugin` done with the key from a safe place
- [ ] First upload manual; later ones `./gradlew publishPlugin` with `PUBLISH_TOKEN`
