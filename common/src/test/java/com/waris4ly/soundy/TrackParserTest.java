package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackParserTest {

	private static TrackData parse(String json) throws IOException {
		return new TrackParser().parse(JsonBrowser.parse(json));
	}

	@Test
	void parsesFullTrack() throws IOException {
		TrackData track = parse("""
				{"id": 42, "title": "Song", "permalink_url": "https://soundcloud.com/artist/song",
				 "duration": 180000, "artwork_url": "https://i1.sndcdn.com/artworks-abc-large.jpg",
				 "user": {"username": "artist"}, "streamable": true, "policy": "ALLOW",
				 "media": {"transcodings": [{"url": "https://api-v2.soundcloud.com/media/x/stream/hls",
				  "snipped": false, "quality": "sq",
				  "format": {"protocol": "hls", "mime_type": "audio/mp4; codecs=\\"mp4a.40.2\\""}}]}}""");

		assertEquals(42, track.getId());
		assertEquals("Song", track.getTitle());
		assertEquals("https://soundcloud.com/artist/song", track.getPermalinkUrl());
		assertEquals(180000, track.getDuration());
		assertEquals("https://i1.sndcdn.com/artworks-abc-t500x500.jpg", track.getArtworkUrl());
		assertEquals("artist", track.getUsername());
		assertTrue(track.isStreamable());
	}

	@Test
	void snipPolicyIsAPreviewAndNotStreamable() throws IOException {
		assertFalse(parse("""
				{"id": 1, "streamable": true, "policy": "SNIP"}""").isStreamable());
	}

	@Test
	void blockPolicyIsRegionBlockedAndNotStreamable() throws IOException {
		// Region-blocked tracks come with streamable true but no transcodings.
		assertFalse(parse("""
				{"id": 1, "streamable": true, "policy": "BLOCK", "media": {"transcodings": []}}""").isStreamable());
	}

	@Test
	void drmTrackIsNotStreamable() throws IOException {
		// The shape the API gives a DRM track (1999052727, 2026-11-25): policy ALLOW
		// and
		// streamable, so the policy check passed it, but every transcoding is
		// encrypted.
		TrackData track = parse("""
				{"id": 1999052727, "streamable": true, "policy": "ALLOW", "media": {"transcodings": [
				 {"url": "u1", "snipped": false, "quality": "sq",
				  "format": {"protocol": "cbc-encrypted-hls", "mime_type": "audio/mp4; codecs=\\"mp4a.40.2\\""}},
				 {"url": "u2", "snipped": false, "quality": "sq",
				  "format": {"protocol": "ctr-encrypted-hls", "mime_type": "audio/mp4; codecs=\\"mp4a.40.2\\""}}]}}""");

		assertFalse(track.isStreamable());
		assertEquals(StreamSelection.Unplayable.DRM, track.getStreamSelection().getUnplayable());
	}

	@Test
	void trackWithoutTranscodingsIsNotStreamable() throws IOException {
		assertEquals(StreamSelection.Unplayable.NO_STREAM, parse("""
				{"id": 1, "streamable": true, "policy": "ALLOW"}""").getStreamSelection().getUnplayable());
	}

	@Test
	void streamableFalseIsNotStreamable() throws IOException {
		assertFalse(parse("""
				{"id": 1, "streamable": false, "policy": "ALLOW"}""").isStreamable());
	}

	@Test
	void missingFieldsFallBackToDefaults() throws IOException {
		TrackData track = parse("""
				{"id": 1}""");

		assertEquals("Unknown Title", track.getTitle());
		assertEquals("Unknown", track.getUsername());
		assertEquals("", track.getPermalinkUrl());
		assertEquals("", track.getArtworkUrl());
	}
}
