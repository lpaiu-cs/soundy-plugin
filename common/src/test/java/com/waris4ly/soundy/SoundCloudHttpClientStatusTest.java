package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundCloudHttpClientStatusTest {

	private int invalidations;

	private final SoundCloudHttpClient client = new SoundCloudHttpClient(new ClientIdProvider() {
		@Override
		public String getClientId(HttpInterface http) {
			return "test";
		}

		@Override
		public void invalidate() {
			invalidations++;
		}
	});

	@Test
	void notFoundCarriesStatusAndIsNotRetried() {
		CannedHttp http = new CannedHttp(404);

		SoundCloudHttpClient.StatusException e = assertThrows(SoundCloudHttpClient.StatusException.class,
				() -> client.get(http, "/resolve?url=x"));

		assertEquals(404, e.getStatusCode());
		assertEquals(1, http.requests);
		assertEquals(0, invalidations);
	}

	@Test
	void unauthorizedRefreshesClientIdAndRetries() throws Exception {
		CannedHttp http = new CannedHttp(401, 200);

		assertEquals("{}", client.get(http, "/resolve?url=x"));
		assertEquals(2, http.requests);
		assertEquals(1, invalidations);
	}

	@Test
	void unauthorizedRefreshesClientIdOnlyOnce() {
		CannedHttp http = new CannedHttp(401, 401, 200);

		SoundCloudHttpClient.StatusException e = assertThrows(SoundCloudHttpClient.StatusException.class,
				() -> client.get(http, "/resolve?url=x"));

		assertEquals(401, e.getStatusCode());
		assertEquals(2, http.requests);
		assertEquals(1, invalidations);
	}

	@Test
	void forbiddenIsNeitherRetriedNorBlamedOnClientId() {
		CannedHttp http = new CannedHttp(403, 200);

		SoundCloudHttpClient.StatusException e = assertThrows(SoundCloudHttpClient.StatusException.class,
				() -> client.get(http, "/resolve?url=x"));

		assertEquals(403, e.getStatusCode());
		assertEquals(1, http.requests);
		assertEquals(0, invalidations);
	}

	@Test
	void rateLimitAndServerErrorsAreRetried() throws Exception {
		CannedHttp http = new CannedHttp(429, 503, 200);

		assertEquals("{}", client.get(http, "/resolve?url=x"));
		assertEquals(3, http.requests);
		assertEquals(0, invalidations);
	}

	@Test
	void statusStaysExactWhenTheUrlContains404() {
		SoundCloudHttpClient.StatusException e = assertThrows(SoundCloudHttpClient.StatusException.class,
				() -> client.get(new CannedHttp(400), "/resolve?url=https%3A%2F%2Fsoundcloud.com%2Fa%2F404"));

		// the message holds the URL, so a text match on "404" would misfire
		assertTrue(e.getMessage().contains("404"));
		assertEquals(400, e.getStatusCode());
	}

	/** Answers each request with the next status and an empty JSON body. */
	private static final class CannedHttp extends HttpInterface {
		final Deque<Integer> statuses;
		int requests;

		CannedHttp(Integer... statuses) {
			super(null, null, false, null);
			this.statuses = new ArrayDeque<>(Arrays.asList(statuses));
		}

		@Override
		public CloseableHttpResponse execute(HttpUriRequest request) {
			requests++;
			return new Response(statuses.removeFirst());
		}
	}

	private static final class Response extends BasicHttpResponse implements CloseableHttpResponse {
		Response(int status) {
			super(HttpVersion.HTTP_1_1, status, null);
			setEntity(new StringEntity("{}", StandardCharsets.UTF_8));
		}

		@Override
		public void close() {
		}
	}
}
