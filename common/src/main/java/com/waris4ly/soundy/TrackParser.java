package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

public class TrackParser {

	public TrackData parse(JsonBrowser track) {
		long id = track.get("id").asLong(0);
		String title = track.get("title").text();
		String permalinkUrl = track.get("permalink_url").text();
		long duration = track.get("duration").asLong(0);
		String artworkUrl = track.get("artwork_url").text();
		String username = track.get("user").get("username").text();

		return new TrackData(id, title != null ? title : "Unknown Title", permalinkUrl != null ? permalinkUrl : "",
				duration, upgradeArtwork(artworkUrl), username != null ? username : "Unknown",
				StreamSelection.of(track));
	}

	private static String upgradeArtwork(String artworkUrl) {
		if (artworkUrl == null || artworkUrl.isEmpty()) {
			return "";
		}
		return artworkUrl.replace("-large.jpg", "-t500x500.jpg");
	}
}
