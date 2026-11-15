package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundyIdentifierTest {

	@Test
	void claimsShortLinks() {
		assertTrue(isShortLink("https://on.soundcloud.com/uUyIXp76UL1oAg179e"));
		assertTrue(isShortLink("https://on.soundcloud.com/uUyIXp76UL1oAg179e/"));
		assertTrue(isShortLink("https://on.soundcloud.com/GPbxC0jTS8Fw6q0BUm?utm_source=clipboard"));

		assertFalse(isShortLink("https://on.soundcloud.com/"));
		assertFalse(isShortLink("https://on.soundcloud.com/a/b"));
		assertFalse(isShortLink("https://soundcloud.com/uUyIXp76UL1oAg179e"));
	}

	@Test
	void parsesPagedSearch() {
		Matcher search = SoundySourceManager.PAGED_SEARCH.matcher("scsearch[20,50]: lofi beats ");
		assertTrue(search.matches());
		assertEquals("20", search.group(1));
		assertEquals("50", search.group(2));
		assertEquals("lofi beats", search.group(3).trim());

		assertFalse(SoundySourceManager.PAGED_SEARCH.matcher("scsearch:lofi").matches());
		assertFalse(SoundySourceManager.PAGED_SEARCH.matcher("scsearch[20]:lofi").matches());
		assertFalse(SoundySourceManager.PAGED_SEARCH.matcher("scsearch[-1,50]:lofi").matches());
		assertFalse(SoundySourceManager.PAGED_SEARCH.matcher("scsearch[1234567890,50]:lofi").matches());
	}

	@Test
	void claimsBothSearchForms() {
		SoundySourceManager source = new SoundySourceManager();
		try {
			// an empty query is answered without a request
			assertSame(AudioReference.NO_TRACK, source.loadItem(null, new AudioReference("scsearch:  ", null)));
			assertSame(AudioReference.NO_TRACK, source.loadItem(null, new AudioReference("scsearch[0,50]:  ", null)));

			assertNull(source.loadItem(null, new AudioReference("scsearch[0,50]lofi", null)));
		} finally {
			source.shutdown();
		}
	}

	private static boolean isShortLink(String identifier) {
		return SoundySourceManager.SHORT_URL.matcher(identifier).matches();
	}
}
