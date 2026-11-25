package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SoundCloudService {

	private static final Pattern LIKES_URL = Pattern.compile("^https?://[^/]+/[^/?#]+/likes(?:[?#].*)?$");

	private final SoundCloudHttpClient client;
	private final TrackParser trackParser = new TrackParser();
	private final PlaylistParser playlistParser = new PlaylistParser();

	public SoundCloudService(SoundCloudHttpClient client) {
		this.client = client;
	}

	public Object resolveUrl(HttpInterface http, String url) throws IOException {
		String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
		String json = client.get(http, "/resolve?url=" + encoded);
		JsonBrowser data = JsonBrowser.parse(json);
		String kind = data.get("kind").text();

		return switch (kind) {
			case "track" -> trackParser.parse(data);
			case "playlist", "system-playlist" -> playlistParser.parse(http, client, data);
			// a profile and its /likes page both resolve to the user; null means
			// nothing to play
			case "user" -> LIKES_URL.matcher(url).matches() ? likes(http, data) : null;
			default -> throw new IOException("Unsupported resource kind '" + kind + "' for: " + url);
		};
	}

	// Like lavaplayer's built-in source: one page of up to 200 likes, liked
	// playlists skipped.
	private PlaylistData likes(HttpInterface http, JsonBrowser user) throws IOException {
		long userId = user.get("id").asLong(0);
		String username = user.get("username").text();
		String json = client.get(http, "/users/" + userId + "/likes?limit=200&offset=0");

		List<TrackData> tracks = new ArrayList<>();
		for (JsonBrowser like : JsonBrowser.parse(json).get("collection").values()) {
			JsonBrowser track = like.get("track");
			if (!track.isNull()) {
				tracks.add(trackParser.parse(track));
			}
		}
		return new PlaylistData(userId, "Liked by " + username, user.get("permalink_url").text() + "/likes", username,
				tracks);
	}

	public List<TrackData> search(HttpInterface http, String query) throws IOException {
		return search(http, query, 0, 10);
	}

	public List<TrackData> search(HttpInterface http, String query, int offset, int limit) throws IOException {
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		String json = client.get(http, "/search/tracks?q=" + encoded + "&offset=" + offset + "&limit=" + limit);
		JsonBrowser root = JsonBrowser.parse(json);

		List<TrackData> results = new ArrayList<>();
		for (JsonBrowser track : root.get("collection").values()) {
			results.add(trackParser.parse(track));
		}
		return results;
	}

	public StreamData getStream(HttpInterface http, long trackId) throws IOException {
		return getStream(http, trackId, null);
	}

	/**
	 * Resolves the stream to play. Picks it with the same {@link StreamSelection}
	 * loading used, and throws {@link UnplayableTrackException} for a track it
	 * rejects: one that loaded before SoundCloud changed it, or one decoded from an
	 * old encoding.
	 */
	public StreamData getStream(HttpInterface http, long trackId, String secretToken) throws IOException {
		// A private track answers 404 without its secret token.
		String json = client.get(http, withSecretToken("/tracks/" + trackId, secretToken));
		TrackData track = trackParser.parse(JsonBrowser.parse(json));
		StreamSelection selection = track.getStreamSelection();
		if (!selection.isPlayable()) {
			throw new UnplayableTrackException(track);
		}

		String url = withSecretToken(selection.getTranscodingUrl(), secretToken);
		return new StreamData(resolveStreamUrl(http, url), selection.getProtocol(), selection.getMimeType());
	}

	private String resolveStreamUrl(HttpInterface http, String transcodingUrl) throws IOException {
		String json = client.getAbsolute(http, transcodingUrl);
		JsonBrowser data = JsonBrowser.parse(json);
		String url = data.get("url").text();
		if (url == null || url.isEmpty()) {
			throw new IOException("Empty stream URL from: " + transcodingUrl);
		}
		return url;
	}

	// The API already puts the token on a private track's transcoding URLs; add
	// it only where it is missing.
	private static String withSecretToken(String url, String secretToken) {
		if (url == null || secretToken == null || url.contains("secret_token=")) {
			return url;
		}
		return url + (url.contains("?") ? "&" : "?") + "secret_token="
				+ URLEncoder.encode(secretToken, StandardCharsets.UTF_8);
	}
}
