package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SOUNDY_IT", matches = "1")
class SoundCloudPlaybackEdgeCasesIT {

	private static final String TRACK_URL = "https://soundcloud.com/jhfly/poetic-justice";

	// A private track whose secret link is public: a fixture of yt-dlp's tests.
	private static final String SECRET_TRACK_URL = "https://soundcloud.com/jaimemf/youtube-dl-test-video-a-y-baw/s-8Pjrp";

	// Policy ALLOW, but every transcoding is cbc- or ctr-encrypted-hls
	// (2026-11-25).
	static final String DRM_TRACK_URL = "https://soundcloud.com/novax21777/newjeans-jersey-club";
	private static final long DRM_TRACK_ID = 1999052727L;

	private final AudioPlayerManager manager = new DefaultAudioPlayerManager();
	private final SoundySourceManager sourceManager = new SoundySourceManager();

	SoundCloudPlaybackEdgeCasesIT() {
		manager.registerSourceManager(sourceManager);
	}

	@AfterEach
	void shutdown() {
		manager.shutdown();
	}

	@Test
	void rejectedSegmentSignatureRefreshesPlaylist() throws Exception {
		AudioTrack loaded = loadTrack(TRACK_URL);
		AtomicInteger playlistLoads = new AtomicInteger();
		AudioTrack track = new SoundyAudioTrack((SoundyAudioTrackInfo) loaded.getInfo(), sourceManager) {
			@Override
			SoundyHlsPlaylistParser.Playlist loadPlaylist(StreamData stream) throws IOException {
				SoundyHlsPlaylistParser.Playlist playlist = super.loadPlaylist(stream);
				// First load only: forge every signature after segment 0, which
				// CloudFront rejects with the same 403 as an expired one.
				return playlistLoads.getAndIncrement() == 0 ? withForgedSignatures(playlist, 1) : playlist;
			}
		};

		AudioPlayer player = manager.createPlayer();
		player.playTrack(track);
		// Segment 0 holds about 10 s, so 1000 frames (20 s) need segment 1 on.
		long frames = pullFrames(player, 1000, 90_000);

		assertTrue(frames >= 1000, "expected playback past the rejected segment, got " + frames + " frames");
		assertEquals(2, playlistLoads.get(), "expected exactly one playlist refresh");
	}

	@Test
	void seekPastEndFinishesTrack() throws Exception {
		AudioTrack track = loadTrack(TRACK_URL);
		AudioPlayer player = manager.createPlayer();
		CompletableFuture<AudioTrackEndReason> ended = new CompletableFuture<>();
		player.addListener(new AudioEventAdapter() {
			@Override
			public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
				ended.complete(endReason);
			}
		});

		track.setPosition(track.getDuration() + 10_000);
		player.playTrack(track);
		long frames = pullFrames(player, 50, 30_000);

		assertEquals(0, frames, "seeking past the end must not restart the track");
		assertEquals(AudioTrackEndReason.FINISHED, ended.get(5, TimeUnit.SECONDS));
	}

	@Test
	void secretLinkPlaysAfterEncodeDecode() throws Exception {
		AudioTrack loaded = loadTrack(SECRET_TRACK_URL);

		// Played from its encoded form, as Lavalink clients hand tracks back.
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		manager.encodeTrack(new MessageOutput(encoded), loaded);
		AudioTrack decoded = manager
				.decodeTrack(new MessageInput(new ByteArrayInputStream(encoded.toByteArray()))).decodedTrack;
		assertEquals(SECRET_TRACK_URL, decoded.getInfo().uri);

		AudioPlayer player = manager.createPlayer();
		player.playTrack(decoded);
		long frames = pullFrames(player, 50, 60_000);
		assertTrue(frames >= 50, "expected the private track to decode, got " + frames + " frames");
	}

	@Test
	void drmTrackFailsOnPlayWithItsCode() throws Exception {
		// Built from its id, as a client hands back a track it loaded before the
		// track turned DRM; loading it now fails (SoundCloudLoaderIT).
		AudioTrack track = new SoundyAudioTrack(new SoundyAudioTrackInfo("NewJeans", "NovaX", 0,
				String.valueOf(DRM_TRACK_ID), DRM_TRACK_URL, DRM_TRACK_ID, null), sourceManager);
		AudioPlayer player = manager.createPlayer();
		CompletableFuture<FriendlyException> failed = new CompletableFuture<>();
		CompletableFuture<AudioTrackEndReason> ended = new CompletableFuture<>();
		player.addListener(new AudioEventAdapter() {
			@Override
			public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
				failed.complete(exception);
			}

			@Override
			public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
				ended.complete(endReason);
			}
		});

		player.playTrack(track);
		long frames = pullFrames(player, 10, 30_000);

		assertEquals(0, frames);
		assertEquals(AudioTrackEndReason.LOAD_FAILED, ended.get(5, TimeUnit.SECONDS));
		// Lavaplayer passes it on unwrapped, so its message is what Lavalink's
		// TrackExceptionEvent carries.
		UnplayableTrackException e = assertInstanceOf(UnplayableTrackException.class, failed.get(5, TimeUnit.SECONDS));
		assertEquals(StreamSelection.Unplayable.DRM, e.getReason());
		assertTrue(e.getMessage().contains("soundy={\"code\":\"SOUNDCLOUD_DRM\",\"id\":1999052727,"), e.getMessage());
	}

	private static SoundyHlsPlaylistParser.Playlist withForgedSignatures(SoundyHlsPlaylistParser.Playlist playlist,
			int fromSegment) {
		List<SoundyHlsPlaylistParser.Segment> segments = new ArrayList<>();
		for (int i = 0; i < playlist.segments.size(); i++) {
			SoundyHlsPlaylistParser.Segment segment = playlist.segments.get(i);
			String url = i < fromSegment ? segment.url : segment.url.replaceAll("Signature=[^&]+", "Signature=AAAA");
			segments.add(new SoundyHlsPlaylistParser.Segment(url, segment.durationMs));
		}
		return new SoundyHlsPlaylistParser.Playlist(playlist.initUrl, segments);
	}

	private AudioTrack loadTrack(String url) throws Exception {
		CompletableFuture<AudioTrack> loaded = new CompletableFuture<>();
		manager.loadItem(new AudioReference(url, null), new AudioLoadResultHandler() {
			@Override
			public void trackLoaded(AudioTrack track) {
				loaded.complete(track);
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist) {
				loaded.completeExceptionally(new AssertionError("expected a track: " + url));
			}

			@Override
			public void noMatches() {
				loaded.completeExceptionally(new AssertionError("no matches: " + url));
			}

			@Override
			public void loadFailed(FriendlyException exception) {
				loaded.completeExceptionally(exception);
			}
		});
		return loaded.get(60, TimeUnit.SECONDS);
	}

	/** Pulls frames until enough arrived, the track ended or the time ran out. */
	private static long pullFrames(AudioPlayer player, long wanted, long timeoutMs) throws InterruptedException {
		long frames = 0;
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (frames < wanted && player.getPlayingTrack() != null && System.currentTimeMillis() < deadline) {
			AudioFrame frame = player.provide();
			if (frame != null && frame.getDataLength() > 0) {
				frames++;
			} else {
				Thread.sleep(5);
			}
		}
		return frames;
	}
}
