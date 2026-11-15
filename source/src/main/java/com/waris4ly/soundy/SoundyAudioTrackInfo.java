package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SoundyAudioTrackInfo extends AudioTrackInfo {

	private static final Pattern SECRET_PERMALINK = Pattern.compile("^https?://[^/]+/[^/]+/[^/]+/(s-[^/?#]+)/?$");

	private final long trackId;

	public SoundyAudioTrackInfo(String title, String author, long length, String identifier, String uri, long trackId,
			String artworkUrl) {
		super(title, author, length, identifier, false, uri, artworkUrl, null);
		this.trackId = trackId;
	}

	public long getTrackId() {
		return trackId;
	}

	/**
	 * Secret token of a private track, or null. A private track's permalink ends
	 * with it (https://soundcloud.com/user/track/s-XXXXX), and the permalink is the
	 * uri, so the token survives encoding without changing the format.
	 */
	public String getSecretToken() {
		Matcher matcher = SECRET_PERMALINK.matcher(uri != null ? uri : "");
		return matcher.matches() ? matcher.group(1) : null;
	}
}
