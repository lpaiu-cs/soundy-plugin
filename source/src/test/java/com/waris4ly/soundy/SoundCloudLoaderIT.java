package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Calls loadItem directly so NO_TRACK (claimed, nothing there) stays distinct
// from null (not ours, let other sources try).
@EnabledIfEnvironmentVariable(named = "SOUNDY_IT", matches = "1")
class SoundCloudLoaderIT {

	private static SoundySourceManager source;

	@BeforeAll
	static void setUp() {
		source = new SoundySourceManager();
	}

	@AfterAll
	static void tearDown() {
		source.shutdown();
	}

	private static AudioItem load(String identifier) {
		return source.loadItem(null, new AudioReference(identifier, null));
	}

	@Test
	void shortLinkLoadsTheTrack() {
		AudioTrack track = assertInstanceOf(AudioTrack.class, load("https://on.soundcloud.com/uUyIXp76UL1oAg179e"));
		assertEquals("https://soundcloud.com/voyageloveblank/9-feat-uruma-pshine", track.getInfo().uri);

		track = assertInstanceOf(AudioTrack.class, load("https://on.soundcloud.com/GPbxC0jTS8Fw6q0BUm"));
		assertEquals("https://soundcloud.com/sebastian-ogryzek-917170/back-it-up-slowed", track.getInfo().uri);
	}

	@Test
	void unknownShortLinkIsNoMatches() {
		assertSame(AudioReference.NO_TRACK, load("https://on.soundcloud.com/doesnotexist000"));
	}

	@Test
	void wwwUrlLoadsTheTrack() {
		AudioTrack track = assertInstanceOf(AudioTrack.class, load("https://www.soundcloud.com/jhfly/poetic-justice"));
		assertEquals("https://soundcloud.com/jhfly/poetic-justice", track.getInfo().uri);
	}

	@Test
	void likesLoadAsAPlaylist() {
		AudioPlaylist likes = assertInstanceOf(AudioPlaylist.class,
				load("https://soundcloud.com/voyageloveblank/likes"));

		assertEquals("Liked by mono sapa", likes.getName());
		assertFalse(likes.getTracks().isEmpty());
		assertTrue(likes.getTracks().size() <= 200);
	}

	@Test
	void userPageIsNoMatches() {
		assertSame(AudioReference.NO_TRACK, load("https://soundcloud.com/voyageloveblank"));
	}

	@Test
	void pagedSearchPassesLimitAndOffset() {
		List<String> first = identifiers(load("scsearch[0,50]:lofi"));
		assertTrue(first.size() > 10 && first.size() <= 50, "limit 50 gave " + first.size());

		List<String> shifted = identifiers(load("scsearch[10,10]:lofi"));
		int start = first.indexOf(shifted.get(0));
		assertTrue(start > 0, "offset 10 should start past the head of the first page, was at " + start);

		int capped = identifiers(load("scsearch[0,500]:lofi")).size();
		assertTrue(capped > 50 && capped <= 200, "limit 500 should be capped at 200, gave " + capped);
	}

	private static List<String> identifiers(AudioItem item) {
		return assertInstanceOf(AudioPlaylist.class, item).getTracks().stream().map(AudioTrack::getIdentifier)
				.collect(Collectors.toList());
	}
}
