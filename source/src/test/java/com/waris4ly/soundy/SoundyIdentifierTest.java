package com.waris4ly.soundy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

	private static boolean isShortLink(String identifier) {
		return SoundySourceManager.SHORT_URL.matcher(identifier).matches();
	}
}
