package com.sambatech.player;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.libraries.mediaframework.exoplayerextensions.ExoplayerWrapper;
import com.google.android.libraries.mediaframework.exoplayerextensions.Video;
import com.google.android.libraries.mediaframework.layeredvideo.PlaybackControlLayer;
import com.google.android.libraries.mediaframework.layeredvideo.SimpleVideoPlayer;
import com.sambatech.player.event.SambaEvent;
import com.sambatech.player.event.SambaEventBus;
import com.sambatech.player.event.SambaEventType;
import com.sambatech.player.event.SambaPlayerListener;
import com.sambatech.player.model.SambaMedia;

/**
 * Controller for SambaPlayer view.
 *
 * @author Leandro Zanol - 7/12/15
 */
public class SambaPlayer extends FrameLayout {

	private SimpleVideoPlayer player;
	private ImaPlayer imaPlayer;
	private SambaMedia media = new SambaMedia();
	private SambaPlayerListener listener;

	public SambaPlayer(Context context, AttributeSet attrs) {
		super(context, attrs);
		applyAttributes(getContext().getTheme().obtainStyledAttributes(attrs, R.styleable.SambaPlayer, 0, 0));

        /*if (!isInEditMode() && media.url != null)
            createPlayer();*/
	}

	/**
	 * Defines/overwrites current media.
	 *
	 * @param media The media to be played.
	 */
	public void setMedia(SambaMedia media) {
		this.media = media;

        //Creating player
        createPlayer();
	}

	public void setListener(SambaPlayerListener listener) {
		this.listener = listener;
	}

	public void play() {

	}

	/**	Player API **/

	public void pause() {
		player.pause();
	}

	public void stop() {
		player.pause();
		// TODO: seekTo(0)
		SambaEventBus.post(new SambaEvent(SambaEventType.STOP));
	}

	// TODO: seek(float secs)

	public void setFullscreen(boolean flag) {
		player.setFullscreen(flag);
	}

	public boolean isFullscreen() {
		return player.isFullscreen();
	}

	public void show() {
		player.show();
	}

	public void hide() {
		player.hide();
	}

	public float getCurrentTime() {
		return player.getCurrentPosition()/1000f;
	}

	public float getDuration() {
		return player.getDuration() / 1000f;
	}

	public View getView() {
		return this;
	}

	private void createPlayer() {
        if (media.url == null || media.url.isEmpty()) {
			Toast.makeText(getContext(), "The requested media has no URL!", Toast.LENGTH_LONG).show();
			return;
		}

		Video.VideoType videoType = Video.VideoType.OTHER;

		switch (media.type.toLowerCase()) {
			/*case "progressive":
				videoType = Video.VideoType.MP4;
				break;*/
			case "hls":
				videoType = Video.VideoType.HLS;
				break;
			case "dash":
				videoType = Video.VideoType.DASH;
				break;
		}

        if (player != null) {
			stop();
			player.release();
		}

		if (imaPlayer != null) {
			imaPlayer.pause();
			imaPlayer.release();
			imaPlayer = null;
		}

        player = new SimpleVideoPlayer((Activity)getContext(), this,
                new Video(media.url, videoType),
                media.title, false);

		player.addPlaybackListener(new ExoplayerWrapper.PlaybackListener() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                Log.i("evt", "state: " + playWhenReady + " " + playbackState);

				switch (playbackState) {
					case 4:
						if (!playWhenReady)
							SambaEventBus.post(new SambaEvent(SambaEventType.PAUSE));
						break;
					/*case 3:
						if (!playWhenReady)
							SambaEventBus.post(new SambaEvent(SambaEventType.PAUSE, "Pause..."));
						break;*/
					case 5:
						SambaEventBus.post(new SambaEvent(SambaEventType.FINISH));
				}
            }

            @Override
            public void onError(Exception e) {
                Log.i("evt", "error", e);
				SambaEventBus.post(new SambaEvent(SambaEventType.ERROR, e));
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                Log.i("evt", "size: " + width + ' ' + height + ' ' + unappliedRotationDegrees + ' ' + pixelWidthHeightRatio);
				SambaEventBus.post(new SambaEvent(SambaEventType.ERROR, width, height, unappliedRotationDegrees, pixelWidthHeightRatio));
            }
        });

		player.setPlayCallback(new PlaybackControlLayer.PlayCallback() {
			@Override
			public void onPlay() {
				SambaEventBus.post(new SambaEvent(SambaEventType.PLAY));
			}
		});
		player.setFullscreenCallback(new PlaybackControlLayer.FullscreenCallback() {
			@Override
			public void onGoToFullscreen() {
				SambaEventBus.post(new SambaEvent(SambaEventType.FULLSCREEN, "Fullscreen"));
			}

			@Override
			public void onReturnFromFullscreen() {
				SambaEventBus.post(new SambaEvent(SambaEventType.FULLSCREEN_EXIT, "Fullscreen exit"));
			}
		});

		// Move the content player's surface layer to the background so that the ad player's surface
		// layer can be overlaid on top of it during ad playback.
		player.moveSurfaceToBackground();

		if (media.adUrl != null && !media.adUrl.isEmpty())
			imaPlayer = new ImaPlayer((Activity)getContext(), this, media.adUrl);
	}

	private void applyAttributes(TypedArray attrs) {
		media.url = attrs.getString(R.styleable.SambaPlayer_url);
		media.title = attrs.getString(R.styleable.SambaPlayer_title);
		attrs.recycle();
	}
}
