package org.webrtc.voiceengine;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;
import androidx.annotation.Nullable;
import java.lang.System;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.messenger.voip.VideoFileCapturer;
import org.webrtc.Logging;
import org.webrtc.ThreadUtils;

public class WebRtcAudioRecord {

  private static final String TAG = "WebRtcAudioRecord";
  private static final int BITS_PER_SAMPLE = 16;
  private static final int CALLBACK_BUFFER_SIZE_MS = 10;
  private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;
  private static final int BUFFER_SIZE_FACTOR = 2;
  private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000;
  private static final int DEFAULT_AUDIO_SOURCE = getDefaultAudioSource();
  private static int audioSource = DEFAULT_AUDIO_SOURCE;

  private final long nativeAudioRecord;
  private @Nullable WebRtcAudioEffects effects;
  private ByteBuffer byteBuffer;
  private ByteBuffer deviceByteBuffer;

  private @Nullable AudioRecord audioRecord;
  private byte[] fakeAudioPcm;
  private int fakeAudioPos;
  private boolean useFakeAudio;
  private @Nullable AudioRecord deviceAudioRecord;
  private @Nullable AudioRecordThread audioThread;

  private static volatile boolean microphoneMute;
  private byte[] emptyBytes;
  private int captureType;
  private int requestedSampleRate = 48000;
  private int requestedChannels = 1;

  public static WebRtcAudioRecord Instance;

  public enum AudioRecordStartErrorCode {
    AUDIO_RECORD_START_EXCEPTION,
    AUDIO_RECORD_START_STATE_MISMATCH,
  }

  public interface WebRtcAudioRecordErrorCallback {
    void onWebRtcAudioRecordInitError(String errorMessage);
    void onWebRtcAudioRecordStartError(AudioRecordStartErrorCode errorCode, String errorMessage);
    void onWebRtcAudioRecordError(String errorMessage);
  }

  private static @Nullable WebRtcAudioRecordErrorCallback errorCallback;

  public static void setErrorCallback(WebRtcAudioRecordErrorCallback errorCallback) {
    Logging.d(TAG, "Set error callback");
    WebRtcAudioRecord.errorCallback = errorCallback;
  }

  public static class AudioSamples {
    private final int audioFormat;
    private final int channelCount;
    private final int sampleRate;
    private final byte[] data;

    private AudioSamples(AudioRecord audioRecord, byte[] data) {
      this.audioFormat = audioRecord.getAudioFormat();
      this.channelCount = audioRecord.getChannelCount();
      this.sampleRate = audioRecord.getSampleRate();
      this.data = data;
    }

    public int getAudioFormat() { return audioFormat; }
    public int getChannelCount() { return channelCount; }
    public int getSampleRate() { return sampleRate; }
    public byte[] getData() { return data; }
  }

  public interface WebRtcAudioRecordSamplesReadyCallback {
    void onWebRtcAudioRecordSamplesReady(AudioSamples samples);
  }

  private static @Nullable WebRtcAudioRecordSamplesReadyCallback audioSamplesReadyCallback;

  public static void setOnAudioSamplesReady(WebRtcAudioRecordSamplesReadyCallback callback) {
    audioSamplesReadyCallback = callback;
  }

  private class AudioRecordThread extends Thread {
    private volatile boolean keepAlive = true;

    public AudioRecordThread(String name) { super(name); }

    @SuppressWarnings("ByteBufferBackingArray")
    @Override
    public void run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
      Logging.d(TAG, "AudioRecordThread" + WebRtcAudioUtils.getThreadInfo());

      while (keepAlive) {
        int bytesRead;
        if (useFakeAudio && fakeAudioPcm != null) {
          int cap = byteBuffer.capacity();
          byte[] tmp = new byte[cap];
          int remaining = fakeAudioPcm.length - fakeAudioPos;
          if (remaining >= cap) {
            System.arraycopy(fakeAudioPcm, fakeAudioPos, tmp, 0, cap);
            fakeAudioPos += cap;
          } else {
            System.arraycopy(fakeAudioPcm, fakeAudioPos, tmp, 0, remaining);
            int needed = cap - remaining;
            fakeAudioPos = needed % fakeAudioPcm.length;
            System.arraycopy(fakeAudioPcm, 0, tmp, remaining, needed);
          }
          if (fakeAudioPos >= fakeAudioPcm.length) fakeAudioPos = 0;
          byteBuffer.rewind();
          byteBuffer.put(tmp);
          byteBuffer.rewind();
          bytesRead = cap;
          try { Thread.sleep(10); } catch (Exception ignore) {}
        } else {
          bytesRead = audioRecord.read(byteBuffer, byteBuffer.capacity());
        }

        int deviceBytesRead;
        if (deviceAudioRecord != null) {
          deviceBytesRead = deviceAudioRecord.read(deviceByteBuffer, deviceByteBuffer.capacity());
        } else {
          deviceBytesRead = 0;
        }
        if (bytesRead == byteBuffer.capacity()) {
          if (microphoneMute) {
            byteBuffer.clear();
            byteBuffer.put(emptyBytes);
          }
          if (bytesRead == deviceBytesRead) {
            deviceByteBuffer.position(0);
            byteBuffer.position(0);
            for (int a = 0; a < bytesRead / 2; a++) {
              int mixed = byteBuffer.getShort(a * 2) + deviceByteBuffer.getShort(a * 2) / 10;
              if (mixed > 32767) mixed = 32767;
              if (mixed < -32768) mixed = -32768;
              byteBuffer.putShort(a * 2, (short) mixed);
            }
          }
          if (keepAlive) {
            try {
              nativeDataIsRecorded(bytesRead, nativeAudioRecord);
            } catch (UnsatisfiedLinkError unsatisfiedLinkError) {
              FileLog.e(unsatisfiedLinkError);
              keepAlive = false;
            }
          }
        } else {
          String errorMessage = "AudioRecord.read failed: " + bytesRead;
          Logging.e(TAG, errorMessage);
          if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
            keepAlive = false;
            reportWebRtcAudioRecordError(errorMessage);
          }
        }
      }

      try {
        if (audioRecord != null) audioRecord.stop();
      } catch (IllegalStateException e) {
        Logging.e(TAG, "AudioRecord.stop failed: " + e.getMessage());
      }
    }

    public void stopThread() {
      Logging.d(TAG, "stopThread");
      keepAlive = false;
    }
  }

  WebRtcAudioRecord(long nativeAudioRecord, int type) {
    Logging.d(TAG, "ctor" + WebRtcAudioUtils.getThreadInfo());
    this.nativeAudioRecord = nativeAudioRecord;
    effects = WebRtcAudioEffects.create();
    captureType = type;
    if (captureType == 2 && Instance == null) Instance = this;
  }

  private void onDestroy() {
    stopDeviceAudioRecord();
    if (Instance == this) Instance = null;
  }

  private boolean enableBuiltInAEC(boolean enable) {
    Logging.d(TAG, "enableBuiltInAEC(" + enable + ')');
    if (effects == null) { Logging.e(TAG, "Built-in AEC is not supported on this platform"); return false; }
    return effects.setAEC(enable);
  }

  private boolean enableBuiltInNS(boolean enable) {
    Logging.d(TAG, "enableBuiltInNS(" + enable + ')');
    if (effects == null) { Logging.e(TAG, "Built-in NS is not supported on this platform"); return false; }
    return effects.setNS(enable);
  }

  private int initRecording(int sampleRate, int channels) {
    if (captureType == 1 && Build.VERSION.SDK_INT < 29) return -1;
    requestedSampleRate = sampleRate;
    requestedChannels = channels;
    Logging.d(TAG, "initRecording(sampleRate=" + sampleRate + ", channels=" + channels + ")");
    if (audioRecord != null) { reportWebRtcAudioRecordInitError("InitRecording called twice without StopRecording."); return -1; }
    final int bytesPerFrame = channels * (BITS_PER_SAMPLE / 8);
    final int framesPerBuffer = sampleRate / BUFFERS_PER_SECOND;
    byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
    byteBuffer.order(ByteOrder.nativeOrder());
    emptyBytes = new byte[byteBuffer.capacity()];
    nativeCacheDirectBufferAddress(byteBuffer, nativeAudioRecord);

    final int channelConfig = channelCountToConfiguration(channels);
    int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
    if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) { reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize); return -1; }
    int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, byteBuffer.capacity());

    if (captureType == 1) {
      if (Build.VERSION.SDK_INT >= 29) {
        try {
          MediaProjection projection = VideoCapturerDevice.getMediaProjection();
          if (projection == null) return -1;
          AudioPlaybackCaptureConfiguration.Builder builder = new AudioPlaybackCaptureConfiguration.Builder(projection);
          builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
          builder.addMatchingUsage(AudioAttributes.USAGE_GAME);
          builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
          AudioRecord.Builder audioRecordBuilder = new AudioRecord.Builder();
          audioRecordBuilder.setAudioPlaybackCaptureConfig(builder.build());
          audioRecordBuilder.setAudioFormat(new AudioFormat.Builder().setChannelMask(channelConfig).setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build());
          audioRecordBuilder.setBufferSizeInBytes(bufferSizeInBytes);
          audioRecord = audioRecordBuilder.build();
        } catch (Throwable e) { reportWebRtcAudioRecordInitError("AudioRecord ctor error: " + e.getMessage()); releaseAudioResources(false); return -1; }
      }
    } else {
      try {
        audioRecord = new AudioRecord(audioSource, sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes);
      } catch (IllegalArgumentException e) { reportWebRtcAudioRecordInitError("AudioRecord ctor error: " + e.getMessage()); releaseAudioResources(false); return -1; }
    }
    if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) { reportWebRtcAudioRecordInitError("Failed to create a new AudioRecord instance"); releaseAudioResources(false); return -1; }
    if (captureType == 0 && effects != null) effects.enable(audioRecord.getAudioSessionId());
    logMainParameters();
    logMainParametersExtended();
    return framesPerBuffer;
  }

  @TargetApi(29)
  public void initDeviceAudioRecord(MediaProjection mediaProjection) {
    if (Build.VERSION.SDK_INT < 29) return;
    final int bytesPerFrame = requestedChannels * (BITS_PER_SAMPLE / 8);
    final int framesPerBuffer = requestedSampleRate / BUFFERS_PER_SECOND;
    deviceByteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
    deviceByteBuffer.order(ByteOrder.nativeOrder());
    final int channelConfig = channelCountToConfiguration(requestedChannels);
    int minBufferSize = AudioRecord.getMinBufferSize(requestedSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
    if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) { reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize); return; }
    int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, deviceByteBuffer.capacity());
    try {
      AudioPlaybackCaptureConfiguration.Builder builder = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection);
      builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
      builder.addMatchingUsage(AudioAttributes.USAGE_GAME);
      AudioRecord.Builder audioRecordBuilder = new AudioRecord.Builder();
      audioRecordBuilder.setAudioPlaybackCaptureConfig(builder.build());
      audioRecordBuilder.setAudioFormat(new AudioFormat.Builder().setChannelMask(channelConfig).setSampleRate(requestedSampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build());
      audioRecordBuilder.setBufferSizeInBytes(bufferSizeInBytes);
      deviceAudioRecord = audioRecordBuilder.build();
    } catch (Throwable e) { reportWebRtcAudioRecordInitError("AudioRecord ctor error: " + e.getMessage()); releaseAudioResources(true); return; }
    if (deviceAudioRecord == null || deviceAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) { reportWebRtcAudioRecordInitError("Failed to create a new AudioRecord instance"); releaseAudioResources(true); return; }
    try { deviceAudioRecord.startRecording(); } catch (IllegalStateException e) { reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION, "AudioRecord.startRecording failed: " + e.getMessage()); return; }
    if (deviceAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
      reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH, "AudioRecord.startRecording failed - incorrect state :" + deviceAudioRecord.getRecordingState());
    }
  }

  @TargetApi(29)
  public void stopDeviceAudioRecord() {
    if (deviceAudioRecord == null) return;
    try { deviceAudioRecord.stop(); } catch (Throwable e) { FileLog.e(e); }
    releaseAudioResources(true);
  }

  public void loadFakeAudio(String path) {
    try {
      android.media.MediaExtractor ex = new android.media.MediaExtractor();
      ex.setDataSource(path);
      int track = -1;
      android.media.MediaFormat fmt = null;
      for (int i = 0; i < ex.getTrackCount(); i++) {
        android.media.MediaFormat f = ex.getTrackFormat(i);
        String mime = f.getString(android.media.MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
      }
      if (track < 0 || fmt == null) { ex.release(); return; }
      ex.selectTrack(track);
      android.media.MediaCodec dec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME));
      dec.configure(fmt, null, null, 0);
      dec.start();
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
      boolean done = false;
      while (!done) {
        int inIdx = dec.dequeueInputBuffer(10000);
        if (inIdx >= 0) {
          ByteBuffer inBuf = dec.getInputBuffer(inIdx);
          int sz = ex.readSampleData(inBuf, 0);
          if (sz < 0) { dec.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); done = true; }
          else { dec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
        }
        int outIdx = dec.dequeueOutputBuffer(info, 10000);
        if (outIdx >= 0) {
          ByteBuffer outBuf = dec.getOutputBuffer(outIdx);
          byte[] chunk = new byte[info.size];
          outBuf.position(info.offset); outBuf.get(chunk);
          out.write(chunk);
          dec.releaseOutputBuffer(outIdx, false);
        }
      }
      dec.release(); ex.release();
      fakeAudioPcm = out.toByteArray();
      fakeAudioPos = 0;
      useFakeAudio = true;
    } catch (Exception e) { FileLog.e(e); }
  }

  private boolean startRecording() {
    Logging.d(TAG, "startRecording");
    try {
      java.io.File fakeFile = VideoFileCapturer.getFakeVideoFile();
      if (fakeFile != null && fakeFile.exists()) {
        loadFakeAudio(fakeFile.getAbsolutePath());
      } else {
        useFakeAudio = false;
        fakeAudioPcm = null;
      }
    } catch (Exception e) { FileLog.e(e); }
    assertTrue(audioRecord != null);
    assertTrue(audioThread == null);
    try { audioRecord.startRecording(); } catch (IllegalStateException e) { reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION, "AudioRecord.startRecording failed: " + e.getMessage()); return false; }
    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH, "AudioRecord.startRecording failed - incorrect state :" + audioRecord.getRecordingState()); return false; }
    audioThread = new AudioRecordThread("AudioRecordJavaThread");
    audioThread.start();
    return true;
  }

  private boolean stopRecording() {
    Logging.d(TAG, "stopRecording");
    assertTrue(audioThread != null);
    audioThread.stopThread();
    if (!ThreadUtils.joinUninterruptibly(audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) { Logging.e(TAG, "Join of AudioRecordJavaThread timed out"); WebRtcAudioUtils.logAudioState(TAG); }
    audioThread = null;
    if (effects != null) effects.release();
    try { audioRecord.stop(); } catch (Throwable e) { FileLog.e(e); }
    releaseAudioResources(false);
    return true;
  }

  private void logMainParameters() {
    Logging.d(TAG, "AudioRecord: session ID: " + audioRecord.getAudioSessionId() + ", channels: " + audioRecord.getChannelCount() + ", sample rate: " + audioRecord.getSampleRate());
  }

  private void logMainParametersExtended() {
    if (Build.VERSION.SDK_INT >= 23) Logging.d(TAG, "AudioRecord: buffer size in frames: " + audioRecord.getBufferSizeInFrames());
  }

  private static void assertTrue(boolean condition) {
    if (!condition) throw new AssertionError("Expected condition to be true");
  }

  private int channelCountToConfiguration(int channels) {
    return (channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO);
  }

  private native void nativeCacheDirectBufferAddress(ByteBuffer byteBuffer, long nativeAudioRecord);
  private native void nativeDataIsRecorded(int bytes, long nativeAudioRecord);

  @SuppressWarnings("NoSynchronizedMethodCheck")
  public static synchronized void setAudioSource(int source) {
    Logging.w(TAG, "Audio source is changed from: " + audioSource + " to " + source);
    audioSource = source;
  }

  private static int getDefaultAudioSource() { return AudioSource.VOICE_COMMUNICATION; }

  public static void setMicrophoneMute(boolean mute) {
    Logging.w(TAG, "setMicrophoneMute(" + mute + ")");
    microphoneMute = mute;
  }

  private void releaseAudioResources(boolean device) {
    Logging.d(TAG, "releaseAudioResources " + device);
    if (device) { if (deviceAudioRecord != null) { deviceAudioRecord.release(); deviceAudioRecord = null; } }
    else { if (audioRecord != null) { audioRecord.release(); audioRecord = null; } }
  }

  private void reportWebRtcAudioRecordInitError(String errorMessage) {
    Logging.e(TAG, "onWebRtcAudioRecordInitError: " + errorMessage);
    WebRtcAudioUtils.logAudioState(TAG);
    if (errorCallback != null) errorCallback.onWebRtcAudioRecordInitError(errorMessage);
  }

  private void reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode errorCode, String errorMessage) {
    Logging.e(TAG, "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
    WebRtcAudioUtils.logAudioState(TAG);
    if (errorCallback != null) errorCallback.onWebRtcAudioRecordStartError(errorCode, errorMessage);
  }

  private void reportWebRtcAudioRecordError(String errorMessage) {
    Logging.e(TAG, "onWebRtcAudioRecordError: " + errorMessage);
    WebRtcAudioUtils.logAudioState(TAG);
    if (errorCallback != null) errorCallback.onWebRtcAudioRecordError(errorMessage);
  }
}
