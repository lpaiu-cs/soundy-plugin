package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIdProviderTest {

	private static final String HOME = "https://soundcloud.com";
	private static final String FIRST = "https://a-v2.sndcdn.com/assets/0-aaaa.js";
	private static final String MIDDLE = "https://a-v2.sndcdn.com/assets/1-bbbb.js";
	private static final String LAST = "https://a-v2.sndcdn.com/assets/2-cccc.js";
	private static final String TRACKER = "https://dwt.soundcloud.com/tags.js";

	// Both attribute orders the homepage uses, plus a script that is not an
	// asset bundle.
	private static final String HOME_HTML = """
			<script crossorigin src="%s"></script>
			<script src="%s"></script>
			<script src="%s" crossorigin></script>
			<script crossorigin src="%s"></script>
			""".formatted(FIRST, TRACKER, MIDDLE, LAST);

	@Test
	void takesClientIdFromLastAssetScript() throws IOException {
		CannedHttp http = new CannedHttp(Map.of(HOME, HOME_HTML, FIRST, script(id("first")), TRACKER,
				script(id("tracker")), LAST, script(id("last"))));

		assertEquals(id("last"), new ClientIdProvider().getClientId(http));
		assertEquals(List.of(HOME, LAST), http.requested);
	}

	@Test
	void fallsBackToEarlierAssetScripts() throws IOException {
		CannedHttp http = new CannedHttp(Map.of(HOME, HOME_HTML, FIRST, script(id("first")), MIDDLE,
				script(id("middle")), LAST, "no client id in this bundle"));

		assertEquals(id("middle"), new ClientIdProvider().getClientId(http));
		assertEquals(List.of(HOME, LAST, MIDDLE), http.requested);
	}

	private static String id(String prefix) {
		return prefix + "0".repeat(32 - prefix.length());
	}

	// The shape the real bundle uses.
	private static String script(String clientId) {
		return "x={client_application_id:46941,client_id:\"" + clientId + "\",client_is_mobile:false}";
	}

	/** Serves canned bodies by URL, 404 for anything else, and records requests. */
	private static final class CannedHttp extends HttpInterface {
		final Map<String, String> bodies;
		final List<String> requested = new ArrayList<>();

		CannedHttp(Map<String, String> bodies) {
			super(null, null, false, null);
			this.bodies = bodies;
		}

		@Override
		public CloseableHttpResponse execute(HttpUriRequest request) {
			String url = request.getURI().toString();
			requested.add(url);
			String body = bodies.get(url);
			return new Response(body == null ? 404 : 200, body);
		}
	}

	private static final class Response extends BasicHttpResponse implements CloseableHttpResponse {
		Response(int status, String body) {
			super(HttpVersion.HTTP_1_1, status, null);
			if (body != null) {
				setEntity(new StringEntity(body, StandardCharsets.UTF_8));
			}
		}

		@Override
		public void close() {
		}
	}
}
