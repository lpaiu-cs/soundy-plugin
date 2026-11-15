package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.waris4ly.soundy.StreamData.Protocol.HLS_FMP4;
import static com.waris4ly.soundy.StreamData.Protocol.PROGRESSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundCloudServiceStreamSelectionTest {

	private static final String MEDIA = "https://api-v2.soundcloud.com/media/soundcloud:tracks:1/";

	private static final String AAC = "audio/mp4; codecs=mp4a.40.2";
	private static final String AAC_SQ = transcoding("aac_160k", "hls", AAC, "sq", false);
	private static final String AAC_LQ = transcoding("aac_96k", "hls", AAC, "lq", false);
	private static final String SNIPPED_AAC_SQ = transcoding("aac_160k", "hls", AAC, "sq", true);
	private static final String ABR_SQ = transcoding("abr_sq", "hls", "audio/mpegurl", "sq", false);
	private static final String MP3 = transcoding("mp3_1_0", "progressive", "audio/mpeg", "sq", false);

	@Test
	void prefersStandardQualityHlsAac() throws IOException {
		assertPicked("aac_160k", HLS_FMP4, select(ABR_SQ, AAC_LQ, AAC_SQ, MP3));
	}

	@Test
	void prefersLowQualityHlsAacOverProgressiveMp3() throws IOException {
		assertPicked("aac_96k", HLS_FMP4, select(ABR_SQ, AAC_LQ, MP3));
	}

	@Test
	void fallsBackToProgressiveMp3() throws IOException {
		assertPicked("mp3_1_0", PROGRESSIVE, select(ABR_SQ, MP3));
	}

	@Test
	void skipsSnippedTranscodings() throws IOException {
		assertPicked("aac_96k", HLS_FMP4, select(SNIPPED_AAC_SQ, AAC_LQ));
		assertThrows(IOException.class, () -> select(SNIPPED_AAC_SQ));
	}

	@Test
	void failsWithoutTranscodings() {
		assertThrows(IOException.class, () -> select());
	}

	@Test
	void sendsSecretTokenOnBothRequests() throws IOException {
		String stream = MEDIA + "aac_160k/stream/hls";
		List<String> expected = List.of("/tracks/1?secret_token=s-8Pjrp", stream + "?secret_token=s-8Pjrp");

		assertEquals(expected, requests(AAC_SQ, "s-8Pjrp"));
		// The API already tags a private track's transcoding URLs: no duplicate.
		assertEquals(expected, requests(AAC_SQ.replace("/stream/hls", "/stream/hls?secret_token=s-8Pjrp"), "s-8Pjrp"));
		assertEquals(List.of("/tracks/1", stream), requests(AAC_SQ, null));
	}

	private static String transcoding(String preset, String protocol, String mime, String quality, boolean snipped) {
		return String.format(
				"{\"url\": \"%s%s/stream/%s\", \"preset\": \"%s\", \"snipped\": %b,"
						+ " \"format\": {\"protocol\": \"%s\", \"mime_type\": \"%s\"}, \"quality\": \"%s\"}",
				MEDIA, preset, protocol, preset, snipped, protocol, mime, quality);
	}

	/**
	 * Runs getStream on a track with these transcodings. Each transcoding URL
	 * resolves to itself, so the result shows which one was picked.
	 */
	private static StreamData select(String... transcodings) throws IOException {
		String track = "{\"media\": {\"transcodings\": [" + String.join(",", transcodings) + "]}}";
		SoundCloudHttpClient client = new SoundCloudHttpClient(new ClientIdProvider()) {
			@Override
			public String get(HttpInterface http, String path) {
				return track;
			}

			@Override
			public String getAbsolute(HttpInterface http, String url) {
				return "{\"url\": \"" + url + "\"}";
			}
		};
		return new SoundCloudService(client).getStream(null, 1);
	}

	/**
	 * The API paths and URLs getStream requests for a track with this transcoding.
	 */
	private static List<String> requests(String transcoding, String secretToken) throws IOException {
		List<String> requests = new ArrayList<>();
		SoundCloudHttpClient client = new SoundCloudHttpClient(new ClientIdProvider()) {
			@Override
			public String get(HttpInterface http, String path) {
				requests.add(path);
				return "{\"media\": {\"transcodings\": [" + transcoding + "]}}";
			}

			@Override
			public String getAbsolute(HttpInterface http, String url) {
				requests.add(url);
				return "{\"url\": \"" + url + "\"}";
			}
		};
		new SoundCloudService(client).getStream(null, 1, secretToken);
		return requests;
	}

	private static void assertPicked(String preset, StreamData.Protocol protocol, StreamData stream) {
		// Matched on the prefix, since getStream may append query parameters.
		assertTrue(stream.getUrl().startsWith(MEDIA + preset + "/"), "picked " + stream.getUrl());
		assertEquals(protocol, stream.getProtocol());
	}
}
