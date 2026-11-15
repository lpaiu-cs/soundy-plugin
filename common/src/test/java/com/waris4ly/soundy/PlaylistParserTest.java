package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaylistParserTest {

	// Never used for I/O, since the fake client answers every call. The parser
	// only fills stubs when it gets one.
	private static final HttpInterface HTTP = new HttpInterface(null, null, false, null);

	/**
	 * Answers /tracks?ids= like the API, minus the unavailable ids, and records
	 * every requested batch.
	 */
	private static final class FakeClient extends SoundCloudHttpClient {
		final List<List<Long>> batches = new ArrayList<>();
		final Set<Long> unavailable;

		FakeClient(Long... unavailable) {
			super(new ClientIdProvider());
			this.unavailable = Set.of(unavailable);
		}

		@Override
		public String get(HttpInterface http, String path) {
			// Read only the ids parameter, so extra query parameters don't matter.
			List<Long> ids = Arrays.stream(path.replaceAll(".*[?&]ids=([0-9,]+).*", "$1").split(",")).map(Long::valueOf)
					.toList();
			batches.add(ids);

			// The API does not answer in request order.
			List<String> found = new ArrayList<>();
			for (long id : ids) {
				if (!unavailable.contains(id)) {
					found.add(0, full(id));
				}
			}
			return "[" + String.join(",", found) + "]";
		}
	}

	private static String full(long id) {
		return "{\"id\": " + id + ", \"title\": \"Track " + id + "\", \"user\": {\"username\": \"artist\"}}";
	}

	// Past the first five entries, playlists list tracks as stubs like this.
	private static String stub(long id) {
		return "{\"id\": " + id
				+ ", \"kind\": \"track\", \"monetization_model\": \"NOT_APPLICABLE\", \"policy\": \"ALLOW\"}";
	}

	private static List<Long> parseIds(FakeClient client, String... entries) throws IOException {
		JsonBrowser playlist = JsonBrowser
				.parse("{\"id\": 1, \"title\": \"Set\", \"tracks\": [" + String.join(",", entries) + "]}");
		return new PlaylistParser().parse(HTTP, client, playlist).getTracks().stream().map(TrackData::getId).toList();
	}

	@Test
	void fillsStubsInPlaylistOrder() throws IOException {
		FakeClient client = new FakeClient();

		assertEquals(List.of(1L, 2L, 3L, 4L, 5L), parseIds(client, full(1), stub(2), full(3), stub(4), stub(5)));
		assertEquals(List.of(List.of(2L, 4L, 5L)), client.batches, "only stubs are fetched");
	}

	@Test
	void fetchesStubsInBatchesOfFifty() throws IOException {
		FakeClient client = new FakeClient();
		String[] stubs = LongStream.rangeClosed(1, 120).mapToObj(PlaylistParserTest::stub).toArray(String[]::new);

		assertEquals(LongStream.rangeClosed(1, 120).boxed().toList(), parseIds(client, stubs));
		assertEquals(List.of(50, 50, 20), client.batches.stream().map(List::size).toList());
	}

	@Test
	void dropsIdsTheApiDoesNotReturn() throws IOException {
		assertEquals(List.of(1L, 3L), parseIds(new FakeClient(2L), full(1), stub(2), stub(3)));
	}
}
