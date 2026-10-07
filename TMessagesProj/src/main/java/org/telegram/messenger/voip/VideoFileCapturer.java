package org.telegram.messenger.voip;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.Surface;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;

import java.io.File;

public class VideoFileCapturer implements VideoCapturer {

    private final String path;
    private SurfaceTextureHelper helper;
    private CapturerObserver observer;
    private MediaPlayer player;
    private Surface surface;
    private boolean firstFrame;

    public VideoFileCapturer(String path) {
        this.path = path;
    }

    public static File getFakeVideoFile() {
        try {
            File[] dirs = ApplicationLoader.applicationContext.getExternalMediaDirs();
            if (dirs == null || dirs.length == 0 || dirs[0] == null) {
                return null;
            }
            dirs[0].mkdirs();
            return new File(dirs[0], "fake.mp4");
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    @Override
    public void initialize(SurfaceTextureHelper helper, Context context, CapturerObserver observer) {
        this.helper = helper;
        this.observer = observer;
    }

    @Override
    public synchronized void startCapture(int width, int height, int fps) {
        if (player != null) {
            return;
        }
        try {
            helper.setTextureSize(width, height);
            firstFrame = true;
            helper.startListening(frame -> {
                if (firstFrame) {
                    firstFrame = false;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (VoIPService.getSharedInstance() != null) {
                            VoIPService.getSharedInstance().onCameraFirstFrameAvailable();
                        }
                    });
                }
                observer.onFrameCaptured(frame);
            });
            surface = new Surface(helper.getSurfaceTexture());
            player = new MediaPlayer();
            player.setDataSource(path);
            player.setSurface(surface);
            player.setLooping(true);
            player.setVolume(0f, 0f);
            player.setOnVideoSizeChangedListener((mp, w, h) -> {
                if (w <= 0 || h <= 0) {
                    return;
                }
                float scale = Math.min(1f, 1280f / Math.max(w, h));
                helper.setTextureSize(Math.round(w * scale), Math.round(h * scale));
            });
            player.prepare();
            player.start();
            observer.onCapturerStarted(true);
        } catch (Throwable e) {
            FileLog.e(e);
            observer.onCapturerStarted(false);
        }
    }

    @Override
    public synchronized void stopCapture() {
        try {
            helper.stopListening();
        } catch (Throwable ignore) {
        }
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignore) {
            }
            player.release();
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        observer.onCapturerStopped();
    }

    @Override
    public void changeCaptureFormat(int width, int height, int fps) {
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean isScreencast() {
        return false;
    }
}
