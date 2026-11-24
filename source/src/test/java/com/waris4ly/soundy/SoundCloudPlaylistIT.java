package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIfEnvironmentVariable(named = "SOUNDY_IT", matches = "1")
class SoundCloudPlaylistIT {

	// Someone else's set, so it can change at any time. The tests check the
	// loaded playlist against the set's live entry list, not fixed counts.
	// (The earlier one, 37dme5m3a58o/sets/alan-walker, was deleted by 2026-11-25.)
	private static final String PLAYLIST_URL = "https://soundcloud.com/black-blood-306351652/sets/alan-walker";

	@Test
	void loadsEveryPlayableEntryInPlaylistOrder() throws Exception {
		List<JsonBrowser> entries = playlistEntries();
		List<Long> loaded = trackIds(load(PLAYLIST_URL));

		// Only the first five entries come fully populated; the rest are id-only
		// stubs that have to be filled. Previews (SNIP) are checked separately,
		// and region-blocked entries (BLOCK) may or may not load. An ALLOW entry
		// can still be one StreamSelection rejects (DRM, no supported stream): a
		// missing entry is a loader bug only if its own /tracks answer plays.
		List<Long> missing = idsWhere(entries, policy -> !"SNIP".equals(policy) && !"BLOCK".equals(policy)).stream()
				.filter(id -> !loaded.contains(id)).filter(SoundCloudPlaylistIT::playsHere).toList();
		assertTrue(missing.isEmpty(), "playable entries missing from the loaded playlist: " + missing);

		List<Long> inPlaylistOrder = idsWhere(entries, policy -> true).stream().filter(loaded::contains).toList();
		assertEquals(inPlaylistOrder, loaded, "tracks should all come from the set, in its order");
	}

	@Test
	void dropsPreviewEntries() throws Exception {
		List<Long> previews = idsWhere(playlistEntries(), "SNIP"::equals);
		assumeFalse(previews.isEmpty(), "the set has no previews (policy SNIP) any more; use one that does");

		List<Long> loaded = trackIds(load(PLAYLIST_URL));
		List<Long> loadedPreviews = previews.stream().filter(loaded::contains).toList();
		assertTrue(loadedPreviews.isEmpty(), "previews were loaded: " + loadedPreviews);
	}

	@Test
	void loadsMobileUrl() {
		AudioItem item = load("https://m.soundcloud.com/alanwalker/faded-slushii-remix-1");

		// Compared by permalink rather than title: the owner can retitle the track
		// without breaking this URL.
		AudioTrack track = assertInstanceOf(AudioTrack.class, item, "mobile URL should resolve to a track");
		assertEquals("https://soundcloud.com/alanwalker/faded-slushii-remix-1", track.getInfo().uri);
	}

	@Test
	void handlesNonExistentUrlGracefully() {
		assertNull(load("https://soundcloud.com/nonexistent-user-1234567/fake-track-not-found"),
				"non-existent URL should be no match");
	}

	/** Loads through a player manager, as Lavalink does; null means no match. */
	private static AudioItem load(String identifier) {
		AudioPlayerManager manager = new DefaultAudioPlayerManager();
		manager.registerSourceManager(new SoundySourceManager());
		try {
			return manager.loadItemSync(identifier);
		} finally {
			manager.shutdown();
		}
	}

	/** The set's raw tracks[] from the API, read without PlaylistParser. */
	private static List<JsonBrowser> playlistEntries() throws IOException {
		return apiGet("/resolve?url=" + URLEncoder.encode(PLAYLIST_URL, StandardCharsets.UTF_8)).get("tracks").values();
	}

	/** Whether StreamSelection plays the track by its own /tracks answer. */
	private static boolean playsHere(long trackId) {
		try {
			return StreamSelection.of(apiGet("/tracks/" + trackId)).isPlayable();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static JsonBrowser apiGet(String path) throws IOException {
		try (HttpInterfaceManager manager = HttpClientTools.createDefaultThreadLocalManager();
				HttpInterface http = manager.getInterface()) {
			return JsonBrowser.parse(new SoundCloudHttpClient(new ClientIdProvider()).get(http, path));
		}
	}

	private static List<Long> idsWhere(List<JsonBrowser> entries, Predicate<String> policy) {
		return entries.stream().filter(entry -> policy.test(entry.get("policy").text()))
				.map(entry -> entry.get("id").asLong(0)).toList();
	}

	private static List<Long> trackIds(AudioItem item) {
		AudioPlaylist playlist = assertInstanceOf(AudioPlaylist.class, item, "expected a playlist");
		return playlist.getTracks().stream().map(track -> ((SoundyAudioTrackInfo) track.getInfo()).getTrackId())
				.toList();
	}
}
