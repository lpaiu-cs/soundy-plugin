package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class SoundCloudServiceLoadingTest {

	// /resolve answers a profile and its /likes page with the same user.
	private static final String USER = """
			{"kind": "user", "id": 7, "username": "mono sapa",
			 "permalink_url": "https://soundcloud.com/voyageloveblank"}""";

	// A liked track, a liked playlist and a liked preview.
	private static final String LIKES = """
			{"collection": [
			 {"kind": "like", "track": {"id": 1, "title": "One", "user": {"username": "a"},
			  "streamable": true, "policy": "ALLOW"}},
			 {"kind": "like", "playlist": {"id": 9, "title": "A set"}},
			 {"kind": "like", "track": {"id": 2, "title": "Two", "user": {"username": "b"},
			  "streamable": true, "policy": "SNIP"}}]}""";

	/** Answers by path prefix and records every requested path. */
	private static final class FakeClient extends SoundCloudHttpClient {
		final Map<String, String> answers;
		final List<String> paths = new ArrayList<>();

		FakeClient(Map<String, String> answers) {
			super(new ClientIdProvider());
			this.answers = answers;
		}

		@Override
		public String get(HttpInterface http, String path) throws IOException {
			paths.add(path);
			for (Map.Entry<String, String> answer : answers.entrySet()) {
				if (path.startsWith(answer.getKey())) {
					return answer.getValue();
				}
			}
			throw new IOException("unexpected request: " + path);
		}
	}

	@Test
	void likesPageBecomesAPlaylistOfLikedTracks() throws IOException {
		FakeClient client = new FakeClient(Map.of("/resolve", USER, "/users/7/likes", LIKES));

		PlaylistData likes = assertInstanceOf(PlaylistData.class,
				new SoundCloudService(client).resolveUrl(null, "https://soundcloud.com/voyageloveblank/likes?si=abc"));

		assertEquals("Liked by mono sapa", likes.getTitle());
		assertEquals(List.of(1L, 2L), likes.getTracks().stream().map(TrackData::getId).toList());
		assertFalse(likes.getTracks().get(1).isStreamable(), "the preview is left to the streamable filter");
		assertEquals("/users/7/likes?limit=200&offset=0", client.paths.get(1));
	}

	@Test
	void userPageResolvesToNothing() throws IOException {
		FakeClient client = new FakeClient(Map.of("/resolve", USER));

		assertNull(new SoundCloudService(client).resolveUrl(null, "https://soundcloud.com/voyageloveblank"));
		assertEquals(1, client.paths.size(), "a profile does not fetch likes");
	}

	@Test
	void searchPassesOffsetAndLimit() throws IOException {
		FakeClient client = new FakeClient(Map.of("/search/tracks", "{\"collection\": []}"));
		SoundCloudService service = new SoundCloudService(client);

		service.search(null, "lofi beats", 20, 50);
		service.search(null, "lofi beats");

		assertEquals(List.of("/search/tracks?q=lofi+beats&offset=20&limit=50",
				"/search/tracks?q=lofi+beats&offset=0&limit=10"), client.paths);
	}
}
