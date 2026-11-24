package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnplayableTrackExceptionTest {

	private static TrackData drmTrack(String title, String author) throws IOException {
		String json = "{\"id\": 7, \"title\": " + UnplayableTrackException.quote(title) + ", \"user\": {\"username\": "
				+ UnplayableTrackException.quote(author) + "},"
				+ " \"permalink_url\": \"https://soundcloud.com/a/b\", \"streamable\": true, \"policy\": \"ALLOW\","
				+ " \"media\": {\"transcodings\": [{\"url\": \"u\", \"snipped\": false, \"quality\": \"sq\","
				+ " \"format\": {\"protocol\": \"ctr-encrypted-hls\", \"mime_type\": \"audio/mp4\"}}]}}";
		return new TrackParser().parse(JsonBrowser.parse(json));
	}

	@Test
	void payloadRoundTripsAnyTitleOnOneLine() throws IOException {
		String title = "Quote \" back\slash\nnew line \u2028 sep } brace 뉴진스";
		UnplayableTrackException e = new UnplayableTrackException(drmTrack(title, "a\tb"));
		String message = e.getMessage();

		// Clients read the message line by line (a stack trace's "Caused by:").
		assertFalse(message.contains("\n") || message.contains("\r") || message.contains("\u2028"), message);

		JsonBrowser payload = JsonBrowser.parse(message.substring(
				message.indexOf(UnplayableTrackException.MARKER) + UnplayableTrackException.MARKER.length()));
		assertEquals("SOUNDCLOUD_DRM", payload.get("code").text());
		assertEquals(7, payload.get("id").asLong(0));
		assertEquals(title, payload.get("title").text());
		assertEquals("a\tb", payload.get("author").text());
		assertEquals("https://soundcloud.com/a/b", payload.get("uri").text());
	}

	@Test
	void isACommonFriendlyException() throws IOException {
		// COMMON: the track is the problem, not the node.
		assertEquals(FriendlyException.Severity.COMMON, new UnplayableTrackException(drmTrack("t", "a")).severity);
	}

	@Test
	void refusesAPlayableTrack() throws IOException {
		TrackData playable = new TrackParser().parse(JsonBrowser.parse("""
				{"id": 1, "streamable": true, "policy": "ALLOW", "media": {"transcodings": [
				 {"url": "u", "snipped": false, "quality": "sq",
				  "format": {"protocol": "hls", "mime_type": "audio/mp4"}}]}}"""));
		assertThrows(IllegalArgumentException.class, () -> new UnplayableTrackException(playable));
	}
}
