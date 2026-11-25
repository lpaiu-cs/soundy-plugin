package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

/**
 * A track that {@link StreamSelection} will not play, thrown both when a direct
 * link loads and when a track is played.
 *
 * Lavalink hands clients only an exception's message: as the load result's
 * innermost "Caused by:" and as a TrackExceptionEvent's message. So the message
 * ends with a one-line JSON object a client can act on, for instance by looking
 * the song up elsewhere:
 *
 * <pre>
 * This SoundCloud track can't be played: it is DRM-protected. soundy={"code":"SOUNDCLOUD_DRM","id":1999052727,"title":"NewJeans","author":"NovaX","duration":122588,"uri":"https://soundcloud.com/novax21777/newjeans-jersey-club"}
 * </pre>
 *
 * {@code duration} is in milliseconds. With the title and author it lets a
 * client tell the same recording from another version (remix, live, music-video
 * edit).
 *
 * The {@code soundy=} marker, the field names and the codes in
 * {@link StreamSelection.Unplayable} are the contract; the sentence before them
 * is for people and may change.
 */
public class UnplayableTrackException extends FriendlyException {

	public static final String MARKER = "soundy=";

	private final transient TrackData track;

	public UnplayableTrackException(TrackData track) {
		super(message(track), Severity.COMMON, null);
		this.track = track;
	}

	public TrackData getTrack() {
		return track;
	}

	public StreamSelection.Unplayable getReason() {
		return track.getStreamSelection().getUnplayable();
	}

	static String message(TrackData track) {
		StreamSelection.Unplayable reason = track.getStreamSelection().getUnplayable();
		if (reason == null) {
			throw new IllegalArgumentException("track " + track.getId() + " is playable");
		}
		return "This SoundCloud track can't be played: " + reason.getReason() + ". " + MARKER + "{\"code\":"
				+ quote(reason.getCode()) + ",\"id\":" + track.getId() + ",\"title\":" + quote(track.getTitle())
				+ ",\"author\":" + quote(track.getUsername()) + ",\"duration\":" + track.getDuration() + ",\"uri\":"
				+ quote(track.getPermalinkUrl()) + "}";
	}

	// Keeps the object on one line: a stack trace prints the message as one
	// "Caused by:" line, and clients read it line by line.
	static String quote(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : (value != null ? value : "").toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20 || c == ' ' || c == ' ') {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}
}
