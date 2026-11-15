package com.waris4ly.soundy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoundyAudioTrackPlaybackTest {

	private static final long[] DURATIONS = {10007L, 9984L, 1407L};

	private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();

	@AfterEach
	void shutdown() {
		prefetchExecutor.shutdownNow();
	}

	@Test
	void expiredSegmentRefreshesOnceAndContinuesAtSameIndex() throws IOException {
		FakeSource source = new FakeSource();
		// Expired while segment 0 played, after old/1 had been prefetched.
		source.rejected.addAll(List.of("old/1", "old/2"));

		assertEquals(List.of("old/init", "old/0", "new/1", "new/2"), readAll(provider(source, 0)));
		assertEquals(1, source.refreshes);
	}

	@Test
	void expiredInitSegmentRefreshesWhenChainOpens() throws IOException {
		FakeSource source = new FakeSource();
		source.rejected.addAll(List.of("old/init", "old/0", "old/1", "old/2"));

		assertEquals(List.of("new/init", "new/1", "new/2"), readAll(provider(source, 1)));
		assertEquals(1, source.refreshes);
	}

	@Test
	void refreshedPlaylistThatDoesNotLineUpFails() {
		FakeSource source = new FakeSource();
		source.rejected.add("old/init");
		source.fresh = playlist("new", 10007L, 9984L);

		assertThrows(IOException.class, provider(source, 0)::next);
	}

	@Test
	void urlRejectedAfterRefreshFailsWithoutAnotherRefresh() {
		FakeSource source = new FakeSource();
		source.rejected.addAll(List.of("old/init", "new/init"));

		assertThrows(IOException.class, provider(source, 0)::next);
		assertEquals(1, source.refreshes);
	}

	@Test
	void chainPastLastSegmentHoldsOnlyInit() throws IOException {
		FakeSource source = new FakeSource();

		assertEquals(List.of("old/init"), readAll(provider(source, DURATIONS.length)));
	}

	@Test
	void seekAtOrPastEndOpensPastLastSegment() {
		List<SoundyHlsPlaylistParser.Segment> segments = playlist("old", DURATIONS).segments; // 21398 ms

		assertEquals(0, SoundyAudioTrack.seekSegmentIndex(segments, 21398, 0));
		assertEquals(2, SoundyAudioTrack.seekSegmentIndex(segments, 21398, 21397));
		assertEquals(3, SoundyAudioTrack.seekSegmentIndex(segments, 21398, 21398));
		assertEquals(3, SoundyAudioTrack.seekSegmentIndex(segments, 21398, 60000));
		// The track length decides the end when known, the playlist total
		// otherwise.
		assertEquals(2, SoundyAudioTrack.seekSegmentIndex(segments, 21500, 21450));
		assertEquals(3, SoundyAudioTrack.seekSegmentIndex(segments, 0, 21398));
	}

	@Test
	void secretTokenComesFromPrivatePermalink() {
		assertEquals("s-8Pjrp",
				info("https://soundcloud.com/jaimemf/youtube-dl-test-video-a-y-baw/s-8Pjrp").getSecretToken());
		assertNull(info("https://soundcloud.com/jhfly/poetic-justice").getSecretToken());
		// A public track whose slug merely starts with "s-".
		assertNull(info("https://soundcloud.com/someone/s-curve").getSecretToken());
		assertNull(info(null).getSecretToken());
	}

	private static SoundyAudioTrackInfo info(String uri) {
		return new SoundyAudioTrackInfo("title", "author", 9927, "1", uri, 1, null);
	}

	private SoundyAudioTrack.Fmp4StreamProvider provider(FakeSource source, int startSegment) {
		return new SoundyAudioTrack.Fmp4StreamProvider(source, prefetchExecutor, playlist("old", DURATIONS),
				startSegment);
	}

	private static SoundyHlsPlaylistParser.Playlist playlist(String generation, long... durations) {
		List<SoundyHlsPlaylistParser.Segment> segments = new ArrayList<>();
		for (int i = 0; i < durations.length; i++) {
			segments.add(new SoundyHlsPlaylistParser.Segment(generation + "/" + i, durations[i]));
		}
		return new SoundyHlsPlaylistParser.Playlist(generation + "/init", segments);
	}

	private static List<String> readAll(SoundyAudioTrack.Fmp4StreamProvider provider) throws IOException {
		List<String> bodies = new ArrayList<>();
		InputStream stream;
		while ((stream = provider.next()) != null) {
			bodies.add(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
		}
		return bodies;
	}

	/** Serves each URL as its own body; rejected URLs answer like a 403. */
	private static class FakeSource implements SoundyAudioTrack.SegmentSource {
		final Set<String> rejected = new HashSet<>();
		SoundyHlsPlaylistParser.Playlist fresh = playlist("new", DURATIONS);
		int refreshes;

		@Override
		public byte[] fetch(String url) {
			return rejected.contains(url) ? null : url.getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public SoundyHlsPlaylistParser.Playlist refresh() {
			refreshes++;
			return fresh;
		}
	}
}
