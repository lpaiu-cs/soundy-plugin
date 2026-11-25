# soundy-plugin
A SoundCloud source manager for Lavaplayer and Lavalink.

SoundCloud dropped their old progressive MP3 streams and switched entirely to fragmented MP4 (AAC) HLS. This source adds proper support for those streams so playback, seeking, and playlists work without issues.

Originally created by [waris4ly](https://github.com/waris4ly).

## Table of Contents
- [Installation](#installation)
  - Installing the plugin in Lavalink v4.
- [Configuration](#configuration)
  - Required settings in `application.yml`.
- [Usage](#usage)
  - Supported URL formats and search syntax.
- [Library use](#library-use)
  - Using the `common` and `source` modules with Lavaplayer directly.
- [Building](#building)
  - Instructions for building from source.
- [Support](#support)
  - Where to get help or report issues.

## Installation
Download `soundy-plugin-<version>.jar` from the [Releases](https://github.com/lpaiu-cs/soundy-plugin/releases) page, put it in Lavalink's `plugins` directory, and restart Lavalink:

```
lavalink/
├── Lavalink.jar
├── application.yml
└── plugins/
    └── soundy-plugin-<version>.jar
```

Keep only one Soundy jar in `plugins`: delete the old one when you upgrade.

Do not list Soundy under `lavalink.plugins`. That list makes Lavalink download plugins from a Maven repository, and Soundy is not published to one that Lavalink can download from.

## Configuration
Add both settings to `application.yml`:

```yaml
lavalink:
  server:
    sources:
      soundcloud: false # required: turns off Lavalink's built-in SoundCloud source

plugins: # top-level key, not under lavalink
  soundy:
    enabled: true # set to false to disable Soundy without removing the jar
```

Lavalink's built-in SoundCloud source uses the same source name, `soundcloud`, and is registered before plugins. If you leave it on, it takes SoundCloud links and searches before Soundy sees them, then fails on SoundCloud's current HLS-only tracks.

## Usage

### Links
- Tracks: `https://soundcloud.com/<user>/<track>`
- Playlists and sets: `https://soundcloud.com/<user>/sets/<playlist>`, including SoundCloud's system playlists (`https://soundcloud.com/discover/sets/...`)
- Likes: `https://soundcloud.com/<user>/likes`
- Secret links: track and playlist links ending in `/s-<token>`
- Mobile links: `https://m.soundcloud.com/...`
- Short links: `https://on.soundcloud.com/<code>`

### Searching
- `scsearch:<query>` returns up to 10 tracks.
- `scsearch[<offset>,<limit>]:<query>` skips `<offset>` results and returns up to `<limit>`, which can be at most 200.

### Tracks that can't be played
A track plays only if SoundCloud offers it as a full-length (not preview) stream in plain HLS AAC (`audio/mp4`) or progressive MP3. Loading and playback check this with the same rule, so a track that loads also plays.

Searches, playlists and likes skip the tracks that fail it. A direct link to one fails to load, and a track that stops passing after it was loaded (for example, played from an encoded track) fails when played. Both errors end with a one-line JSON object after `soundy=`:

```
This SoundCloud track can't be played: it is DRM-protected. soundy={"code":"SOUNDCLOUD_DRM","id":1999052727,"title":"NewJeans","author":"NovaX","uri":"https://soundcloud.com/novax21777/newjeans-jersey-club"}
```

In Lavalink's `/v4/loadtracks` this is the last `Caused by:` line of `data.causeStackTrace`, and in a `TrackExceptionEvent` it is `exception.message`. The marker, the field names and the codes are stable; the sentence before them may change.

| `code` | Why |
|---|---|
| `SOUNDCLOUD_DRM` | Every full-length stream is encrypted (`cbc-encrypted-hls`, `ctr-encrypted-hls`). |
| `SOUNDCLOUD_PREVIEW` | Only a 30-second preview (policy `SNIP`, or only snipped streams). |
| `SOUNDCLOUD_BLOCKED` | Blocked in this region (policy `BLOCK`). |
| `SOUNDCLOUD_NOT_STREAMABLE` | SoundCloud marks it as not streamable. |
| `SOUNDCLOUD_NO_STREAM` | No stream in a supported format. |

## Library use
`source` provides `SoundySourceManager` for Lavaplayer. `common` holds the HTTP client, `client_id` lookup and parsers it uses.

```java
AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
playerManager.registerSourceManager(new SoundySourceManager());
// If you also register Lavaplayer's remote sources, leave out its own SoundCloud source (same source name):
AudioSourceManagers.registerRemoteSources(playerManager, SoundCloudAudioSourceManager.class);
```

Each release attaches `soundy-source-<version>.jar` and `soundy-common-<version>.jar`. Put both on your classpath next to `dev.arbjerg:lavaplayer`; `source` needs `common`.

The release workflow also publishes them to GitHub Packages (`https://maven.pkg.github.com/lpaiu-cs/soundy-plugin`, as `com.waris4ly.soundy:common` and `com.waris4ly.soundy:source`). GitHub Packages requires authentication even for public packages, so your build needs a GitHub token with the `read:packages` scope to resolve them.

## Building
To build the project from source:

```bash
./gradlew build
```

The plugin jar will be generated at:
```
plugin/build/libs/soundy-plugin-x.x.x.jar
```

To run all tests including integration tests against live SoundCloud endpoints:
```bash
SOUNDY_IT=1 ./gradlew :source:test
```

## Support
If you encounter any bugs, have questions, or need assistance, please open an issue on GitHub.
