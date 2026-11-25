package com.waris4ly.soundy;

public class TrackData {

	private final long id;
	private final String title;
	private final String permalinkUrl;
	private final long duration;
	private final String artworkUrl;
	private final String username;
	private final StreamSelection streamSelection;

	public TrackData(long id, String title, String permalinkUrl, long duration, String artworkUrl, String username,
			StreamSelection streamSelection) {
		this.id = id;
		this.title = title;
		this.permalinkUrl = permalinkUrl;
		this.duration = duration;
		this.artworkUrl = artworkUrl;
		this.username = username;
		this.streamSelection = streamSelection;
	}

	public long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getPermalinkUrl() {
		return permalinkUrl;
	}

	public long getDuration() {
		return duration;
	}

	public String getArtworkUrl() {
		return artworkUrl;
	}

	public String getUsername() {
		return username;
	}

	/** Whether the track plays here, and from which stream. */
	public StreamSelection getStreamSelection() {
		return streamSelection;
	}

	public boolean isStreamable() {
		return streamSelection.isPlayable();
	}
}
