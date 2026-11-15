package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAacTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegFileLoader;
import com.sedmelluq.discord.lavaplayer.container.mpeg.reader.MpegFileTrackProvider;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackInfo;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class SoundyAudioTrack extends DelegatedAudioTrack {
	private static final Logger log = LoggerFactory.getLogger(SoundyAudioTrack.class);

	private final SoundySourceManager sourceManager;

	public SoundyAudioTrack(SoundyAudioTrackInfo trackInfo, SoundySourceManager sourceManager) {
		super(trackInfo);
		this.sourceManager = sourceManager;
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		StreamData stream = resolveStream();
		log.debug("Track {} resolved to {} stream ({})", trackInfo.identifier, stream.getProtocol(),
				stream.getMimeType());

		if (stream.getProtocol() == StreamData.Protocol.PROGRESSIVE) {
			processProgressive(stream, executor);
		} else {
			processHlsFmp4(stream, executor);
		}
	}

	private StreamData resolveStream() throws IOException {
		SoundyAudioTrackInfo info = (SoundyAudioTrackInfo) trackInfo;
		try (HttpInterface http = sourceManager.getHttpInterface()) {
			return sourceManager.getService().getStream(http, info.getTrackId(), info.getSecretToken());
		}
	}

	// Package-private so a live test can hand out a playlist whose signatures
	// the CDN rejects.
	SoundyHlsPlaylistParser.Playlist loadPlaylist(StreamData stream) throws IOException {
		try (HttpInterface http = sourceManager.getHttpInterface()) {
			return SoundyHlsPlaylistParser.parse(http, URI.create(stream.getUrl()));
		}
	}

	private void processProgressive(StreamData stream, LocalAudioTrackExecutor executor) throws Exception {
		try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
			try (PersistentHttpStream httpStream = new PersistentHttpStream(httpInterface, new URI(stream.getUrl()),
					Long.MAX_VALUE)) {
				processDelegate(new Mp3AudioTrack(trackInfo, httpStream), executor);
			}
		}
	}

	private void processHlsFmp4(StreamData stream, LocalAudioTrackExecutor executor) throws Exception {
		SoundyHlsPlaylistParser.Playlist playlist = loadPlaylist(stream);
		log.debug("Parsed HLS playlist for track {}: {} segments", trackInfo.identifier, playlist.segments.size());

		Fmp4Player player = new Fmp4Player(playlist, executor);
		try {
			executor.executeProcessingLoop(player::provideFrames, player::performSeek, true);
		} finally {
			player.close();
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new SoundyAudioTrack((SoundyAudioTrackInfo) trackInfo, sourceManager);
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return sourceManager;
	}

	static int segmentIndexFor(List<SoundyHlsPlaylistParser.Segment> segments, long positionMs) {
		if (segments.isEmpty()) {
			return 0;
		}

		long cumulativeMs = 0;
		for (int i = 0; i < segments.size(); i++) {
			Long duration = segments.get(i).durationMs;
			if (duration == null) {
				return i;
			}
			cumulativeMs += duration;
			if (positionMs < cumulativeMs) {
				return i;
			}
		}
		return segments.size() - 1;
	}

	static long totalDurationMs(List<SoundyHlsPlaylistParser.Segment> segments) {
		long totalMs = 0;
		for (SoundyHlsPlaylistParser.Segment segment : segments) {
			if (segment.durationMs != null) {
				totalMs += segment.durationMs;
			}
		}
		return totalMs;
	}

	/**
	 * Segment to open a chain at for a seek. A position at or past the end maps one
	 * past the last segment: that chain has no media, so the reader hits EOF and
	 * the track ends instead of restarting.
	 */
	static int seekSegmentIndex(List<SoundyHlsPlaylistParser.Segment> segments, long trackLengthMs, long positionMs) {
		long endMs = trackLengthMs > 0 ? trackLengthMs : totalDurationMs(segments);
		return endMs > 0 && positionMs >= endMs ? segments.size() : segmentIndexFor(segments, positionMs);
	}

	private class Fmp4Player implements SegmentSource {
		private static final int MAX_FETCH_RETRIES = 3;
		private static final long RETRY_BACKOFF_MS = 150L;

		private final LocalAudioTrackExecutor executor;

		private SoundyHlsPlaylistParser.Playlist playlist;
		private ChainedInputStream chain;
		private MpegTrackConsumer consumer;
		private MpegFileTrackProvider reader;
		private Fmp4StreamProvider provider;

		private Fmp4Player(SoundyHlsPlaylistParser.Playlist playlist, LocalAudioTrackExecutor executor) {
			this.playlist = playlist;
			this.executor = executor;
		}

		private void provideFrames() throws Exception {
			ensureOpened();
			reader.provideFrames();
		}

		private void performSeek(long positionMs) throws Exception {
			long position = Math.max(0, positionMs);
			log.debug("Seeking track {} to {} ms", trackInfo.identifier, position);
			openChain(seekSegmentIndex(playlist.segments, trackInfo.length, position), position);
		}

		private void ensureOpened() throws Exception {
			if (reader == null) {
				openChain(0, 0);
			}
		}

		private void openChain(int segmentIndex, long positionMs) throws Exception {
			closeChain();

			if (playlist.initUrl == null) {
				throw new FriendlyException("HLS playlist has no EXT-X-MAP init segment.",
						FriendlyException.Severity.SUSPICIOUS, null);
			}

			provider = new Fmp4StreamProvider(this, sourceManager.getPrefetchExecutor(), playlist, segmentIndex);
			chain = new ChainedInputStream(provider);

			MpegFileLoader loader = new MpegFileLoader(new NonSeekableInputStream(chain));
			loader.parseHeaders();

			consumer = selectAudioTrack(loader.getTrackList());
			if (consumer == null) {
				throw new FriendlyException("No supported audio track in SoundCloud stream.",
						FriendlyException.Severity.SUSPICIOUS, null);
			}
			consumer.initialise();

			long segmentStartMs = totalDurationMs(
					playlist.segments.subList(0, Math.min(segmentIndex, playlist.segments.size())));
			consumer.seekPerformed(positionMs, segmentStartMs);

			reader = loader.loadReader(consumer);
			if (reader == null) {
				throw new FriendlyException("Unknown MP4 format.", FriendlyException.Severity.SUSPICIOUS, null);
			}
		}

		private MpegTrackConsumer selectAudioTrack(List<MpegTrackInfo> tracks) {
			for (MpegTrackInfo track : tracks) {
				if ("soun".equals(track.handler) && "mp4a".equals(track.codecName)) {
					log.debug("Selected audio codec: {}", track.codecName);
					return new MpegAacTrackConsumer(executor.getProcessingContext(), track);
				}
			}
			return null;
		}

		private void closeChain() {
			if (provider != null) {
				provider.close();
				provider = null;
			}
			if (consumer != null) {
				try {
					consumer.close();
				} catch (Exception e) {
					log.debug("Failed to close track consumer", e);
				}
				consumer = null;
			}
			reader = null;
			chain = null;
		}

		private void close() {
			closeChain();
		}

		@Override
		public byte[] fetch(String url) throws IOException {
			IOException lastException = null;
			for (int attempt = 0; attempt < MAX_FETCH_RETRIES; attempt++) {
				if (attempt > 0) {
					try {
						Thread.sleep(RETRY_BACKOFF_MS * attempt);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted during segment fetch", e);
					}
				}
				// Not HttpStreamTools.streamContent: it throws a RuntimeException
				// on a bad status, which escaped these retries.
				try (HttpInterface http = sourceManager.getHttpInterface();
						CloseableHttpResponse response = http.execute(new HttpGet(url))) {
					int status = response.getStatusLine().getStatusCode();
					if (status == 403) {
						return null;
					}
					if (!HttpClientTools.isSuccessWithContent(status)) {
						throw new IOException("HTTP " + status + " for segment: " + url);
					}
					return EntityUtils.toByteArray(response.getEntity());
				} catch (IOException e) {
					lastException = e;
				}
			}
			throw lastException != null ? lastException : new IOException("Failed to fetch segment: " + url);
		}

		@Override
		public SoundyHlsPlaylistParser.Playlist refresh() throws IOException {
			log.debug("Track {}: segment URL rejected, refreshing the HLS playlist", trackInfo.identifier);
			StreamData stream = resolveStream();
			if (stream.getProtocol() != StreamData.Protocol.HLS_FMP4) {
				throw new IOException("Track " + trackInfo.identifier + " no longer resolves to an HLS stream");
			}
			playlist = loadPlaylist(stream);
			return playlist;
		}
	}

	/** Segment I/O of {@link Fmp4StreamProvider}; tests substitute a fake. */
	interface SegmentSource {
		/**
		 * @return the response body, or null when the CDN rejects the URL's signature
		 *         (HTTP 403)
		 */
		byte[] fetch(String url) throws IOException;

		/** @return a freshly signed playlist for the same stream */
		SoundyHlsPlaylistParser.Playlist refresh() throws IOException;
	}

	static class Fmp4StreamProvider implements ChainedInputStream.Provider, AutoCloseable {
		private final SegmentSource source;
		private final ExecutorService prefetchExecutor;
		private final int startSegment;

		private SoundyHlsPlaylistParser.Playlist playlist;
		private int cursor = 0;
		private Future<byte[]> pending;

		Fmp4StreamProvider(SegmentSource source, ExecutorService prefetchExecutor,
				SoundyHlsPlaylistParser.Playlist playlist, int startSegment) {
			this.source = source;
			this.prefetchExecutor = prefetchExecutor;
			this.playlist = playlist;
			this.startSegment = startSegment;
		}

		@Override
		public InputStream next() throws IOException {
			String url = urlFor(cursor);
			if (url == null) {
				return null;
			}

			byte[] data;
			Future<byte[]> prefetched = pending;
			pending = null;

			if (prefetched != null) {
				try {
					data = prefetched.get();
				} catch (ExecutionException e) {
					throw new IOException("Prefetch failed for " + url, e.getCause());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Prefetch interrupted", e);
				}
			} else {
				data = source.fetch(url);
			}

			if (data == null) {
				data = fetchFromRefreshedPlaylist();
			}

			cursor++;

			String upcoming = urlFor(cursor);
			if (upcoming != null) {
				pending = prefetchExecutor.submit(() -> source.fetch(upcoming));
			}

			return new ByteArrayInputStream(data);
		}

		// Signed URLs expire: the playlist's within minutes, the segments' about
		// two hours past the track's length, so a long pause outlives them. A
		// rejected URL gets one refresh, then playback continues at the same index.
		private byte[] fetchFromRefreshedPlaylist() throws IOException {
			SoundyHlsPlaylistParser.Playlist fresh = source.refresh();
			if (fresh.initUrl == null || !durations(fresh).equals(durations(playlist))) {
				throw new IOException("Refreshed HLS playlist does not line up with the one being played");
			}
			playlist = fresh;

			byte[] data = source.fetch(urlFor(cursor));
			if (data == null) {
				throw new IOException("HLS segment still rejected after refreshing the playlist");
			}
			return data;
		}

		private static List<Long> durations(SoundyHlsPlaylistParser.Playlist playlist) {
			return playlist.segments.stream().map(segment -> segment.durationMs).toList();
		}

		private String urlFor(int index) {
			if (index == 0) {
				return playlist.initUrl;
			}
			int segment = startSegment + index - 1;
			if (segment >= playlist.segments.size()) {
				return null;
			}
			return playlist.segments.get(segment).url;
		}

		@Override
		public void close() {
			if (pending != null) {
				pending.cancel(true);
				pending = null;
			}
		}
	}
}
