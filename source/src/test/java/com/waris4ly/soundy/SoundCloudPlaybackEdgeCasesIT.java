package com.waris4ly.soundy;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SOUNDY_IT", matches = "1")
class SoundCloudPlaybackEdgeCasesIT {

	private static final String TRACK_URL = "https://soundcloud.com/jhfly/poetic-justice";

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
