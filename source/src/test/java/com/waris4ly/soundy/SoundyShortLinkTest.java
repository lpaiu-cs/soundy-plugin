package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// A loopback server stands in for on.soundcloud.com.
class SoundyShortLinkTest {

	private HttpServer server;
	private HttpInterfaceManager httpManager;
	private String base;

	@BeforeEach
	void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/track", exchange -> answer(exchange, 302, "https://soundcloud.com/a/b?si=1"));
		server.createContext("/unknown", exchange -> answer(exchange, 404, null));
		server.createContext("/no-redirect", exchange -> answer(exchange, 200, null));
		server.createContext("/hop", exchange -> answer(exchange, 302, base + "/landing"));
		server.createContext("/landing", exchange -> answer(exchange, 200, null));
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		httpManager = HttpClientTools.createDefaultThreadLocalManager();
	}

	@AfterEach
	void tearDown() throws Exception {
		httpManager.close();
		server.stop(0);
	}

	private static void answer(HttpExchange exchange, int status, String location) throws IOException {
		if (location != null) {
			exchange.getResponseHeaders().add("Location", location);
		}
		exchange.sendResponseHeaders(status, -1);
		exchange.close();
	}

	@Test
	void returnsTheRedirectTargetWithoutLoadingIt() throws Exception {
		try (HttpInterface http = httpManager.getInterface()) {
			assertEquals("https://soundcloud.com/a/b?si=1", SoundySourceManager.followShortLink(http, base + "/track"));
		}
	}

	@Test
	void unknownCodeMeansNoMatches() throws Exception {
		try (HttpInterface http = httpManager.getInterface()) {
			assertNull(SoundySourceManager.followShortLink(http, base + "/unknown"));
		}
	}

	@Test
	void answerWithoutRedirectIsAnError() throws Exception {
		try (HttpInterface http = httpManager.getInterface()) {
			assertThrows(IOException.class, () -> SoundySourceManager.followShortLink(http, base + "/no-redirect"));
		}
	}

	@Test
	void sharedContextStillFollowsRedirectsAfterwards() throws Exception {
		try (HttpInterface http = httpManager.getInterface()) {
			SoundySourceManager.followShortLink(http, base + "/track");

			try (CloseableHttpResponse response = http.execute(new HttpGet(base + "/hop"))) {
				assertEquals(200, response.getStatusLine().getStatusCode());
			}
		}
	}
}
