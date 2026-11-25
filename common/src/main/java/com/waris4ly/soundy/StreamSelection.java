package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

/**
 * The one rule for whether a SoundCloud track plays here, and from which
 * stream: it needs a transcoding that is not a preview, in plain (unencrypted)
 * HLS audio/mp4 or progressive MP3.
 *
 * Loading ({@link TrackParser}) and playback
 * ({@link SoundCloudService#getStream}) both judge the track's API object with
 * {@link #of}, so a track that loads is a track that plays. Before, loading
 * read only the policy: a DRM track (policy ALLOW, every transcoding
 * cbc-/ctr-encrypted-hls) loaded and then failed once played.
 */
public final class StreamSelection {

	/** Why a track does not play here. The code is what clients match on. */
	public enum Unplayable {
		/** Policy SNIP, or only 30-second preview transcodings. */
		PREVIEW("SOUNDCLOUD_PREVIEW", "it is a 30-second preview"),
		/** Every full-length transcoding is encrypted (cbc-/ctr-encrypted-hls). */
		DRM("SOUNDCLOUD_DRM", "it is DRM-protected"),
		/** Policy BLOCK: blocked in this region, no transcodings. */
		BLOCKED("SOUNDCLOUD_BLOCKED", "it is blocked in this region"),
		/** The API marks it as not streamable. */
		NOT_STREAMABLE("SOUNDCLOUD_NOT_STREAMABLE", "SoundCloud marks it as not streamable"),
		/** No transcoding in a format this source decodes. */
		NO_STREAM("SOUNDCLOUD_NO_STREAM", "it has no stream in a supported format");

		private final String code;
		private final String reason;

		Unplayable(String code, String reason) {
			this.code = code;
			this.reason = reason;
		}

		public String getCode() {
			return code;
		}

		public String getReason() {
			return reason;
		}
	}

	private final Unplayable unplayable;
	private final String transcodingUrl;
	private final StreamData.Protocol protocol;
	private final String mimeType;

	private StreamSelection(Unplayable unplayable, String transcodingUrl, StreamData.Protocol protocol,
			String mimeType) {
		this.unplayable = unplayable;
		this.transcodingUrl = transcodingUrl;
		this.protocol = protocol;
		this.mimeType = mimeType;
	}

	/** Judges a track object as /tracks, /resolve, search and playlists give it. */
	public static StreamSelection of(JsonBrowser track) {
		String policy = track.get("policy").text();
		if ("BLOCK".equalsIgnoreCase(policy)) {
			return unplayable(Unplayable.BLOCKED);
		}
		if ("SNIP".equalsIgnoreCase(policy)) {
			return unplayable(Unplayable.PREVIEW);
		}
		if (!track.get("streamable").asBoolean(false)) {
			return unplayable(Unplayable.NOT_STREAMABLE);
		}

		String hlsAacSq = null;
		String hlsAacOther = null;
		String progressiveMp3 = null;
		boolean sawPreview = false;
		boolean sawEncrypted = false;

		for (JsonBrowser t : track.get("media").get("transcodings").values()) {
			if (t.get("snipped").asBoolean(false)) {
				sawPreview = true;
				continue;
			}

			String protocol = t.get("format").get("protocol").text();
			String mime = t.get("format").get("mime_type").text();
			String url = t.get("url").text();
			if (protocol == null || mime == null || url == null) {
				continue;
			}

			// cbc-encrypted-hls, ctr-encrypted-hls and the older encrypted-hls
			if (protocol.contains("encrypted")) {
				sawEncrypted = true;
				continue;
			}

			// No break: HLS AAC is preferred wherever it is listed.
			if ("hls".equals(protocol) && mime.startsWith("audio/mp4")) {
				if ("sq".equals(t.get("quality").text())) {
					hlsAacSq = hlsAacSq != null ? hlsAacSq : url;
				} else {
					hlsAacOther = hlsAacOther != null ? hlsAacOther : url;
				}
			} else if ("progressive".equals(protocol) && mime.contains("mpeg") && progressiveMp3 == null) {
				progressiveMp3 = url;
			}
		}

		if (hlsAacSq != null) {
			return new StreamSelection(null, hlsAacSq, StreamData.Protocol.HLS_FMP4, "audio/mp4");
		}
		if (hlsAacOther != null) {
			return new StreamSelection(null, hlsAacOther, StreamData.Protocol.HLS_FMP4, "audio/mp4");
		}
		if (progressiveMp3 != null) {
			return new StreamSelection(null, progressiveMp3, StreamData.Protocol.PROGRESSIVE, "audio/mpeg");
		}
		// A full-length stream that is only encrypted says more than a preview next
		// to it.
		if (sawEncrypted) {
			return unplayable(Unplayable.DRM);
		}
		return unplayable(sawPreview ? Unplayable.PREVIEW : Unplayable.NO_STREAM);
	}

	private static StreamSelection unplayable(Unplayable reason) {
		return new StreamSelection(reason, null, null, null);
	}

	public boolean isPlayable() {
		return unplayable == null;
	}

	/** Why the track does not play, or null when it does. */
	public Unplayable getUnplayable() {
		return unplayable;
	}

	/** API URL of the picked transcoding; null when unplayable. */
	public String getTranscodingUrl() {
		return transcodingUrl;
	}

	public StreamData.Protocol getProtocol() {
		return protocol;
	}

	public String getMimeType() {
		return mimeType;
	}
}
