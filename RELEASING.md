# Releasing Tempo

How to build and publish a Tempo release (APKs, AABs, GitHub Releases, F-Droid/IzzyOnDroid).
Reading this once is enough; everything below is scripted and reproducible.

## 1. How releases work

Pushing a tag that matches `*.*.*` (e.g. `3.10.0` or `v3.10.0`) triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml). That workflow:

1. builds all three flavors (`tempo`, `notquitemy`, `play`) in release,
2. signs the artifacts with the upload keystore stored in repository secrets,
3. attaches the **3 signed APKs** to the GitHub Release,
4. uploads the **3 AABs** as workflow artifacts (they are not attached to the release),
5. uses `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` as the release
   notes when that file exists, otherwise GitHub's auto-generated notes.

Names to expect: the **workflow artifacts** are `apk-signed` / `aab-signed` when the
signing material is present, and `apk-unsigned` / `aab-unsigned` on a secret-less dry run
(the name follows what is actually inside, so `gh run download apk-signed` can never
quietly hand you unsigned files). The files **attached to the GitHub Release** are renamed
to `tempo-v<version>-<flavor>.apk` — e.g. `tempo-v3.10.0-notquitemy.apk` — which is *not*
the legacy `app-tempo-release.apk` name that older releases and download scripts assume.
The AABs are staged under the same scheme (`tempo-v<version>-<flavor>.aab`) but stay inside
the workflow artifacts; they are never attached to the release.

PRs and pushes to `main` are covered by a separate workflow, see [§7](#7-pr-ci).

## 2. One-time setup: signing secrets

Generate an upload keystore (PKCS12; since JDK 9 `keytool` writes PKCS12 even when the
file is named `.jks`). Keep the alias and both passwords safe and **back the keystore up
off-machine**: if it is lost you cannot publish updates under the same signing identity,
which for Google Play means no further updates for that `applicationId`.

```bash
keytool -genkeypair -v \
  -keystore release.jks -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias tempo
```

Encode it as a single line for the secret. The result **is the private key**, so write it
outside the working tree (`$TMPDIR` or your home), never next to `release.jks` inside the
repo (on macOS `base64` has no `-w0`: use `base64 -i release.jks | tr -d '\n'`):

```bash
base64 -w0 release.jks > ~/tempo-keystore.b64
```

Set the four repository secrets (exact names, `gh` reads the value from stdin), then drop
the temporary encoding:

```bash
gh secret set ANDROID_KEYSTORE_BASE64 --repo QuinsZouls/tempo < ~/tempo-keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD --repo QuinsZouls/tempo
gh secret set ANDROID_KEY_ALIAS --repo QuinsZouls/tempo
gh secret set ANDROID_KEY_PASSWORD --repo QuinsZouls/tempo
rm -f ~/tempo-keystore.b64
```

| Secret | Holds |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64 on one line |
| `ANDROID_KEYSTORE_PASSWORD` | store password (`-storepass`) |
| `ANDROID_KEY_ALIAS` | key alias (`-alias`) |
| `ANDROID_KEY_PASSWORD` | key password (`-keypass`) |

Check them with `gh secret list --repo QuinsZouls/tempo`.

> **Never commit the keystore.** Keep `release.jks`, `keystore.b64` and
> `keystore.properties` out of the repo — ideally outside the working tree altogether, as
> above. Before you `git add` anything, confirm every signing path is ignored:
>
> ```bash
> git check-ignore -v release.jks keystore.b64 keystore.properties
> ```
>
> All three must print a matching `.gitignore` rule (`*.jks`, `keystore.b64`,
> `keystore.properties`). A path that prints nothing is trackable: **never `git add` it**,
> extend `.gitignore` and commit that first, before doing any local signing work.

## 3. Cutting a release

1. Bump both version fields in [`app/build.gradle`](app/build.gradle) → `android.defaultConfig`.
   They are manual literals (currently `versionCode 26`, `versionName '3.9.0'`); nothing
   derives them from git.
2. Add the changelog the release notes and F-Droid will pick up, named after the **new**
   versionCode: `fastlane/metadata/android/en-US/changelogs/27.txt`.
3. Commit both changes on `main`, push the branch, and make sure CI is green.
4. Tag the release commit with the versionName and push the tag:

   ```bash
   git tag 3.10.0
   git push origin 3.10.0
   ```

   `v3.10.0` also works (the trigger glob is `*.*.*`). The version that ends up in the
   artifacts comes from `app/build.gradle`, not from the tag name.

> **Bump before tagging — `3.9.0` is already taken.** The tree currently carries
> `versionCode 26` / `versionName '3.9.0'`, which is also what upstream
> `CappielloAntonio/tempo` has on `master`; upstream has already published a `3.9.0`
> GitHub Release (its `app-tempo-release.apk` reports versionCode 25 / versionName 3.9.0,
> signed with upstream's key), and F-Droid ships
> `com.cappielloantonio.notquitemy.tempo` at versionCode 25 / 3.8.1. The **first** release
> cut from this fork therefore has to go to at least `versionCode 27` /
> `versionName '3.10.0'`, plus `changelogs/27.txt`. Tagging `3.9.0` from here would put a
> second, differently-signed build of upstream's version label on the wire (no existing
> install can be upgraded across keys, and 26 is the code upstream reserves for its next
> release), so bump both fields first.

> The tag **must** match `versionName`, or the run fails on purpose. It is a guard against
> shipping a release whose label and binary disagree.

## 4. Dry run / signed artifacts without publishing

Dispatch the release workflow on a **branch** to build without publishing — the publish
job is tag-gated, so a branch dispatch never creates a GitHub Release:

```bash
gh workflow run release.yml --repo QuinsZouls/tempo --ref main
```

Then download the artifacts from the run page: `apk-signed` / `aab-signed` when the
signing secrets are present, `apk-unsigned` / `aab-unsigned` on a secret-less run
(`gh run download <run-id>` after listing it with `gh run list`).

The "no secrets ⇒ unsigned build, run stays green" behaviour applies to **branch**
dispatches only: there the verify step prints a `::warning::` per flavor and the run
passes, which is what makes the workflow usable on a fork. A **tag** run — pushed *or*
dispatched — hard-fails when the signing material is missing, by design.

> **Dispatching on a tag is not a dry run.** `gh workflow run release.yml --ref <tag>`
> sets the git ref for the run, so with a tag the publish job's gate is satisfied and the
> release **is** published for that tag. That is the intended way to re-run a failed
> release without pushing a new tag; on a branch it is a dry run, on a tag it is not.

## 5. Building a signed release locally

`app/build.gradle` reads signing material from the environment first, then from a
`keystore.properties` file at the repo root. Either option:

```bash
# Option A: environment variables
export ANDROID_KEYSTORE_FILE=/abs/path/to/release.jks
export ANDROID_KEYSTORE_PASSWORD=…
export ANDROID_KEY_ALIAS=…
export ANDROID_KEY_PASSWORD=…
bash gradlew :app:assembleRelease :app:bundleRelease
```

```properties
# Option B: keystore.properties at the repo root (next to settings.gradle, git-ignored)
storeFile=/abs/path/to/release.jks
storePassword=…
keyAlias=tempo
keyPassword=…
```

`ANDROID_KEYSTORE_FILE` / `storeFile` accept an absolute path or one relative to the
repository root. Environment variables win over `keystore.properties`. An incomplete set
(missing password or alias) leaves the build unsigned instead of failing — but a keystore
path that does not exist is a hard error from `app/build.gradle`.

Outputs: APKs under `app/build/outputs/apk/<flavor>/release/`, bundles under
`app/build/outputs/bundle/<flavor>Release/`. Without any keystore the release task still
succeeds and writes `app-<flavor>-release-unsigned.apk`.

Use `bash gradlew …` rather than `./gradlew …`: `gradlew` is committed executable, but
invoking it through `bash` also survives a checkout that lost the bit
(`core.fileMode=false`, Windows). Sanity-check the committed mode with
`git ls-files -s gradlew`; if it ever shows `100644`, fix it for everyone:
`git update-index --chmod=+x gradlew`.

## 6. What each flavor is

| Flavor | applicationId | Where it ships |
| --- | --- | --- |
| `tempo` | `com.cappielloantonio.tempo` | IzzyOnDroid |
| `notquitemy` | `com.cappielloantonio.notquitemy.tempo` | F-Droid |
| `play` | `com.cappielloantonio.play.tempo` | Google Play |

AABs are only for the Google Play upload and are never attached to a GitHub Release; the
release page carries APKs, which is what users install directly. Uploading to Play is a
manual step in Play Console — nothing in this repo automates it.

## 7. PR CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on pull requests targeting
`main`, on pushes to `main`, and on demand. It builds debug and **unsigned** release APKs
for all three flavors, so the proguard/R8 path gets exercised on every PR without any
secret; the release build simply has no keystore available and produces `*-unsigned.apk`.
A second job, `signing-smoke`, covers what that build cannot reach: it generates a
throwaway keystore in the runner's temp dir and runs `:app:assembleRelease` +
`:app:bundleRelease` with it, then `apksigner verify --print-certs` every APK, fails if
Gradle emitted any `*-unsigned.apk`, and asserts the three AABs exist. The signed release
+ AAB path is therefore proven on every PR with no repository secret, instead of first
running on a tag push.
It does not run tests or lint — the repo has neither — and it validates the committed
Gradle wrapper before using it.

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Tag run fails immediately with `::error::` from the meta step | Tag ≠ `versionName` in `app/build.gradle` | Bump `versionName`/`versionCode`, commit, re-tag (see the next row) |
| Tag run fails before signing, secrets reported missing | Secrets not set on this repo, or set on an environment — `release.yml` declares no `environment:`, so environment-scoped secrets are invisible to it and the four secrets must be **repository** secrets | `gh secret list --repo QuinsZouls/tempo` (it lists repository secrets only, which is exactly what the run can see), then re-run the failed job |
| Signing step fails with a keystore/alias error | Wrong `ANDROID_KEY_ALIAS`, or a store password that is not the key password | Verify with `keytool -list -v -keystore release.jks`, then re-set the two password secrets |
| Wrong `versionName` shipped in a release | Tag pushed too early, or the version was not bumped before tagging | Bump `app/build.gradle`, commit, re-tag. Drop the bad pair first with `gh release delete 3.10.0 --cleanup-tag` (or `gh release delete 3.10.0` followed by `git push origin :refs/tags/3.10.0` if the tag was pushed separately) |
| `gradlew: Permission denied` | The exec bit got lost in this checkout (`core.fileMode=false`, Windows) — `gradlew` itself is committed executable | Run `bash gradlew …`, or restore the bit for everyone if `git ls-files -s gradlew` shows `100644`: `git update-index --chmod=+x gradlew` |
