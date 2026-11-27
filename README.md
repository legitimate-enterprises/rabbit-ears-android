# Rabbit Ears for Android
The Android app for [Rabbit Ears](https://github.com/legitimate-enterprises/rabbit-ears),
a self-hosted open-source media server. It is for anyone who runs a Rabbit
Ears server or is invited to one, on a phone or a tablet.

------------------------------------------------------------------------
## How It Works
- A Kotlin app in two Gradle modules: `api`, the client generated from the
  server's OpenAPI spec, and `app`, everything else.
- The app talks only to a Rabbit Ears server, through that server's REST
  API. `openapi.yaml` is the server's spec at the release the app is built
  against. `make spec VERSION=<server version>` replaces it with another
  release's and regenerates `api`; `make lint` fails when the two disagree.
- Unit tests run on the JVM, with no emulator.
- `make test-e2e` downloads a movie from a real server, resumes it from half
  and decodes it. The server is built from its repository at the tag for
  `openapi.yaml`'s version, cloned under `build/`. Set
  `RABBIT_EARS_SERVER_SRC` to a checkout of that repository to use it
  instead.
- `make deploy` tags `main` `v<version>` once CI is green there. The tag's
  release job signs the App Bundle with the upload key and sends it to
  Google Play's Internal testing track.

Limitations: promotion beyond Internal testing is done by hand in Play
Console, as is an app's first upload.

------------------------------------------------------------------------
## Requirements
- macOS with Homebrew, `make` and `git`.
- JDK 21 and the Android SDK, which `make deps` installs.
- `make test-e2e`: Go and `ffmpeg`, which it installs, and Python 3.

------------------------------------------------------------------------
*End of README*
