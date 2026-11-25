package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
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
	// What the API lists for a DRM track (1999052727, 2026-11-25): the same
	// presets,
	// each only as cbc- and ctr-encrypted-hls.
	private static final String CBC_AAC_SQ = transcoding("aac_160k", "cbc-encrypted-hls", AAC, "sq", false);
	private static final String CTR_AAC_SQ = transcoding("aac_160k", "ctr-encrypted-hls", AAC, "sq", false);
	private static final String CBC_AAC_LQ = transcoding("aac_96k", "cbc-encrypted-hls", AAC, "lq", false);
	private static final String CTR_AAC_LQ = transcoding("aac_96k", "ctr-encrypted-hls", AAC, "lq", false);
	private static final String CBC_ABR = transcoding("abr_sq", "cbc-encrypted-hls", "audio/mpegurl", "sq", false);
	private static final String CTR_ABR = transcoding("abr_sq", "ctr-encrypted-hls", "audio/mpegurl", "sq", false);

	@Test
	void prefersStandardQualityHlsAac() throws IOException {
		assertPicked("aac_160k", HLS_FMP4, select(ABR_SQ, AAC_LQ, AAC_SQ, MP3));
	}

	@Test
	void prefersLowQualityHlsAacOverProgressiveMp3() throws IOException {
		assertPicked("aac_96k", HLS_FMP4, select(ABR_SQ, AAC_LQ, MP3));
	}

	@Test
	void prefersHlsAacEvenWhenMp3IsListedFirst() throws IOException {
		assertPicked("aac_160k", HLS_FMP4, select(MP3, AAC_SQ));
		assertPicked("aac_96k", HLS_FMP4, select(MP3, AAC_LQ));
	}

	@Test
	void fallsBackToProgressiveMp3() throws IOException {
		assertPicked("mp3_1_0", PROGRESSIVE, select(ABR_SQ, MP3));
	}

	@Test
	void skipsSnippedTranscodings() throws IOException {
		assertPicked("aac_96k", HLS_FMP4, select(SNIPPED_AAC_SQ, AAC_LQ));
		assertRejected(StreamSelection.Unplayable.PREVIEW, SNIPPED_AAC_SQ);
	}

	@Test
	void failsWithoutTranscodings() {
		assertRejected(StreamSelection.Unplayable.NO_STREAM);
	}

	@Test
	void rejectsADrmTrackWithItsTitleAndCode() {
		UnplayableTrackException e = assertRejected(StreamSelection.Unplayable.DRM, CBC_AAC_SQ, CTR_AAC_SQ, CBC_AAC_LQ,
				CTR_AAC_LQ, CBC_ABR, CTR_ABR);

		assertTrue(
				e.getMessage().endsWith("soundy={\"code\":\"SOUNDCLOUD_DRM\",\"id\":1,\"title\":\"Song\","
						+ "\"author\":\"artist\",\"duration\":200000,\"uri\":\"https://soundcloud.com/artist/song\"}"),
				e.getMessage());
	}

	@Test
	void skipsEncryptedTranscodingsForAPlainOne() throws IOException {
		assertPicked("aac_96k", HLS_FMP4, select(CBC_AAC_SQ, CTR_AAC_SQ, AAC_LQ));
		assertPicked("mp3_1_0", PROGRESSIVE, select(CTR_AAC_SQ, MP3));
	}

	@Test
	void encryptedOverPreviewIsDrm() {
		assertRejected(StreamSelection.Unplayable.DRM, SNIPPED_AAC_SQ, CTR_AAC_SQ);
	}

	/**
	 * The bug this guards: loading judged a track by its policy and playback by its
	 * transcodings, so a DRM track loaded and then failed. Both now ask
	 * StreamSelection; for every track here they must agree.
	 */
	@Test
	void loadingAndPlaybackAgree() throws IOException {
		String[][] tracks = {{AAC_SQ}, {MP3}, {ABR_SQ}, {SNIPPED_AAC_SQ}, {SNIPPED_AAC_SQ, AAC_LQ}, {CTR_AAC_SQ, MP3},
				{CBC_AAC_SQ, CTR_AAC_SQ, CBC_AAC_LQ, CTR_AAC_LQ, CBC_ABR, CTR_ABR}, {}};
		for (String[] transcodings : tracks) {
			boolean loads = new TrackParser().parse(JsonBrowser.parse(track(transcodings))).isStreamable();
			boolean plays;
			try {
				select(transcodings);
				plays = true;
			} catch (UnplayableTrackException e) {
				plays = false;
			}
			assertEquals(loads, plays, "load and play disagree on " + String.join(", ", transcodings));
		}
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

	/** A /tracks/1 answer: a streamable ALLOW track with these transcodings. */
	private static String track(String... transcodings) {
		return "{\"id\": 1, \"title\": \"Song\", \"user\": {\"username\": \"artist\"}, \"duration\": 200000,"
				+ " \"permalink_url\": \"https://soundcloud.com/artist/song\", \"streamable\": true,"
				+ " \"policy\": \"ALLOW\", \"media\": {\"transcodings\": [" + String.join(",", transcodings) + "]}}";
	}

	private static UnplayableTrackException assertRejected(StreamSelection.Unplayable reason, String... transcodings) {
		UnplayableTrackException e = assertThrows(UnplayableTrackException.class, () -> select(transcodings));
		assertEquals(reason, e.getReason());
		return e;
	}

	/**
	 * Runs getStream on a track with these transcodings. Each transcoding URL
	 * resolves to itself, so the result shows which one was picked.
	 */
	private static StreamData select(String... transcodings) throws IOException {
		String track = track(transcodings);
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
				return track(transcoding);
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
