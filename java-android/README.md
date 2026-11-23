# optikpi_inapp

The Java Android SDK of [optikpi-inapp-sdk](https://github.com/gamingcrafts/optikpi-inapp-sdk): OptiKPI
in-app pop-ups for native Android apps (Java). Add the library and call it at login. From then on it:
- fetches the player's waiting pop-ups,
- draws them over your app,
- reports impression, click and dismiss.

You write no pop-up UI.

It depends on OkHttp, AndroidX Activity/AppCompat, and `SharedPreferences` for the event queue. Min SDK
24. Activities should extend `ComponentActivity` / `AppCompatActivity` so the system back key closes
blocking pop-ups.

## Integration in five steps

**1. Add the dependency**, pinned to a release tag. Check out the repo (or a submodule) and include the
Gradle build at `java-android/`:

```kotlin
// settings.gradle.kts
includeBuild("../path/to/optikpi-inapp-sdk/java-android")
```

```kotlin
// app/build.gradle.kts
dependencies {
  implementation(project(":library"))
}
```

Pin the same commit as tag `java-android-v0.1.0` (or your chosen release) when you vendor or submodule
the repo.

**2. Run a Gradle sync** (`./gradlew :library:assemble` from `java-android/` once, to verify the module).

**3. Pop-ups attach automatically** on each `Activity` via lifecycle callbacks (overlay on
`android.R.id.content`). Call `OptikpiInApp.didChangeRoute` when you navigate if the default activity
class name is not your path (recommended for all apps).

**4. Call `init` on login**, and at app start when you restore a saved session. `playerToken` comes from
your server: see
[`docs/player-token-server.md`](../docs/player-token-server.md).

```java
import com.optikpi.inapp.OptikpiInApp;

OptikpiInApp.init(
    this,
    "...",                         // the workspace's in-app key, sent as a Bearer token
    loginResponse.optikpiPlayerToken,
    "https://app.optikpi.com/apigw",
    link -> navigateTo(link),               // optional
    data -> handleMessageClick(data),     // optional
    List.of("/dashboard/games/play", "/dashboard/deposit"),
    null                                  // OptikpiInApp.mockSource(this) for demos
);
```

Whenever your server gives you a new token:

```java
OptikpiInApp.setPlayerToken(this, newToken);
```

Report screen paths on navigation:

```java
OptikpiInApp.didChangeRoute(this, "/dashboard/home");
```

**5. Call `logout` on logout:**

```java
OptikpiInApp.logout(this);   // stops fetching, drops this player's waiting pop-ups
```

Pass an `Application` context on the first `init` so foreground hooks register.

## Options

| `init` argument | Default | |
|---|---|---|
| `onDeepLink` | none | Receives a `deeplink` action value. Without it, the pop-up just closes. |
| `onCustomAction` | none | Receives a `custom` action's key/value pairs after close and click tracking. |
| `blockedRoutes` | `[]` | Screens that never show a pop-up. A path blocks itself and everything under it. |
| `source` | the pull API | `OptikpiInApp.mockSource(context)` serves bundled layout templates; needs no real token. |

`baseUrl` is required: your environment's API gateway, `https://{dev\|qa\|app}.optikpi.com/apigw`.

Other members:
- `OptikpiInApp.setDebug(true)` logs with the `[OptikpiInApp]` prefix.
- `OptikpiInApp.showTest(context, content)` draws a pop-up immediately, ignoring every rule, and sends no events.

## What the package does

- **Fetches** on `init`, when the app comes back to the foreground, and on each screen change, at most once per `nextCheckAfterSec` (default 5 min). It never fetches in the background.
- **Reads the token** for the player and workspace, without verifying it (the server does). If it can't read them, it logs that and fetches nothing until `setPlayerToken`.
- **Pauses on a refused token.** On a `401` or `403` it stops fetching and sending and keeps queued events, until `setPlayerToken`.
- **Shows** at most one pop-up per screen visit, newest first. It never shows one on a blocked route or after it expires.
- **Acts on taps:** `url` opens externally; `deeplink` and `custom` call your handlers; Android back closes blocking layouts when the activity uses `ComponentActivity`.
- **Tracks** impression, click and dismiss through a persistent queue; events go to the `popup` ingest webhook.

## Development

From `java-android/`:

```bash
./gradlew :library:test    # unit tests, including ../contract/fixtures
```

The parsing tests read the shared fixtures in `../contract/fixtures`, so they run from a checkout of the
whole repo.

[`API.md`](../API.md) is the backend contract. `CHANGELOG.md` lists releases.

## License

MIT, see [`LICENSE`](../LICENSE).