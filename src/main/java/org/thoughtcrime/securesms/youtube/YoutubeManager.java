package org.thoughtcrime.securesms.youtube;

import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebResourceResponse;
import androidx.annotation.Nullable;
import chat.delta.rpc.Rpc;
import com.b44t.messenger.DcContext;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * Glue between WebxdcActivity and the youtube classes: owns per-instance {@link YoutubeP2pServer}s
 * (initiator side) and per-instance viewer .part files (viewer side). All youtube-specific
 * WebxdcActivity hooks delegate here so WebxdcActivity keeps working unchanged when youtube support
 * is never used.
 */
public final class YoutubeManager {

  private static final String TAG = "YoutubeManager";

  /** Delivers a realtime protocol message on behalf of a webxdc instance. */
  public interface RealtimeSender {
    void send(String json);
  }

  /** Creates the realtime sender for an instance (usually wraps rpc.sendWebxdcRealtimeData). */
  public interface RealtimeSenderFactory {
    RealtimeSender create(int accountId, int msgId, Rpc rpc);
  }

  /** State held per webxdc instance message. */
  public static final class Instance {
    public final YoutubeP2pServer p2pServer;
    public final YoutubeViewerState viewerState;

    Instance(YoutubeP2pServer p2pServer, YoutubeViewerState viewerState) {
      this.p2pServer = p2pServer;
      this.viewerState = viewerState;
    }
  }

  private static final Map<Long, Instance> instances = new ConcurrentHashMap<>();

  private static long key(int accountId, int msgId) {
    return ((long) accountId << 32) | (msgId & 0xFFFFFFFFL);
  }

  private YoutubeManager() {}

  /** Gets or creates the youtube state for a webxdc instance. */
  public static synchronized Instance getInstance(
      int accountId,
      int msgId,
      @Nullable DcContext dcContext,
      @Nullable Rpc rpc,
      @Nullable RealtimeSenderFactory senderFactory) {
    long k = key(accountId, msgId);
    Instance inst = instances.get(k);
    if (inst == null) {
      if (dcContext == null) {
        return null;
      }
      RealtimeSender sender =
          senderFactory != null
              ? senderFactory.create(accountId, msgId, rpc)
              : defaultSender(accountId, msgId, rpc);
      instances.put(
          k,
          new Instance(
              new YoutubeP2pServer(accountId, msgId, dcContext, rpc, sender::send),
              new YoutubeViewerState(dcContext)));
      inst = instances.get(k);
    }
    return inst;
  }

  private static @Nullable RealtimeSender defaultSender(
      final int accountId, final int msgId, final Rpc rpc) {
    if (rpc == null) {
      return null;
    }
    return json -> {
      try {
        // 16 KB frames arrive as JSON int arrays; that is what the realtime bridge accepts.
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        Integer[] arr = new Integer[data.length];
        for (int i = 0; i < data.length; i++) {
          arr[i] = (int) data[i];
        }
        rpc.sendWebxdcRealtimeData(accountId, msgId, java.util.Arrays.asList(arr));
      } catch (Exception e) {
        Log.w(TAG, "sendWebxdcRealtimeData failed", e);
      }
    };
  }

  /**
   * Handles an incoming realtime payload for a webxdc instance. Returns true if it was consumed as
   * a youtube-protocol request (initiator-side serving happens on a background thread).
   */
  public static boolean handleRealtime(
      int accountId, int instanceMsgId, byte[] data, DcContext dcContext, Rpc rpc) {
    if (data == null || data.length < 2 || data[0] != '{') {
      return false;
    }
    YoutubeProtocol.Message msg = YoutubeProtocol.parse(new String(data, StandardCharsets.UTF_8));
    if (msg == null) {
      return false;
    }
    if (!msg.type.equals(YoutubeProtocol.TYPE_REQ)
        && !msg.type.equals(YoutubeProtocol.TYPE_REQ_META)) {
      // adv/meta/cf/eof/err are consumed by the viewer-side JS.
      return false;
    }
    final Instance inst = getInstance(accountId, instanceMsgId, dcContext, rpc, null);
    if (inst == null || !inst.p2pServer.hasVideo(msg.videoId)) {
      return false;
    }
    org.thoughtcrime.securesms.util.Util.runOnAnyBackgroundThread(
        () -> inst.p2pServer.handleMessage(msg));
    return true;
  }

  /** Registers a video being watched locally (initiator side) so viewers can request ranges. */
  public static void trackVideo(
      int accountId, int msgId, String videoId, String title, DcContext dcContext, Rpc rpc) {
    Instance inst = getInstance(accountId, msgId, dcContext, rpc, null);
    if (inst != null) {
      inst.p2pServer.trackVideo(videoId, title);
    }
  }

  /** Called from WebxdcActivity.onDestroy; shuts down state for this instance. */
  public static void shutdown(int accountId, int msgId) {
    Instance inst = instances.remove(key(accountId, msgId));
    if (inst != null) {
      inst.p2pServer.shutdown();
      inst.viewerState.closeAll();
    }
  }

  // ---- called from WebxdcActivity's InternalJSApi (WebView JS bridge thread) ----

  /** Resolves a youtube stream; returns JSON {size,mime,title,url} or null on failure. */
  public static @Nullable String ytFetchUrl(
      int accountId, int msgId, String videoId, DcContext dcContext, Rpc rpc) {
    try {
      YoutubeStreamFetcher.StreamInfo info = YoutubeStreamFetcher.resolveStream(videoId);
      Instance inst = getInstance(accountId, msgId, dcContext, rpc, null);
      if (inst != null) {
        inst.p2pServer.trackVideo(videoId, info.title);
      }
      return new JSONObject()
          .put("url", info.url)
          .put("size", info.size)
          .put("mime", info.mime)
          .put("title", info.title)
          .toString();
    } catch (Exception e) {
      Log.w(TAG, "ytFetchUrl failed", e);
      return null;
    }
  }

  /**
   * Resolves the stream for a video and stores metadata on the initiator-side server. Called on a
   * background thread before serving; returns JSON {size,mime,title} or null on failure.
   */
  public static @Nullable String resolveForInstance(
      int accountId, int msgId, String videoId, DcContext dcContext, Rpc rpc) {
    try {
      Instance inst = getInstance(accountId, msgId, dcContext, rpc, null);
      if (inst == null) {
        return null;
      }
      YoutubeStreamFetcher.StreamInfo info = YoutubeStreamFetcher.resolveStream(videoId);
      inst.p2pServer.trackVideo(videoId, info.title);
      return new JSONObject()
          .put("size", info.size)
          .put("mime", info.mime)
          .put("title", info.title)
          .toString();
    } catch (Exception e) {
      Log.w(TAG, "resolveForInstance failed", e);
      return null;
    }
  }

  /** Reads len bytes at offset from the local sparse .part file, base64-encoded. */
  public static @Nullable String readVideoChunk(
      int accountId, int msgId, String videoId, long start, int len) {
    Instance inst = instances.get(key(accountId, msgId));
    if (inst == null) {
      return null;
    }
    return inst.viewerState.readChunk(videoId, start, len);
  }

  /** Writes viewer-received frame bytes at the given absolute offset into the .part file. */
  public static void writeVideoChunk(
      int accountId, int msgId, String videoId, long start, String b64) {
    Instance inst = instances.get(key(accountId, msgId));
    if (inst != null) {
      inst.viewerState.writeChunk(videoId, start, b64);
    }
  }

  /**
   * True if the URL is a youtube-player range request from player.js: /yt/&lt;videoId&gt;.mp4 with
   * query params start/len (or meta=1).
   */
  public static boolean isVideoRangeRequest(String rawUrl) {
    Uri uri = Uri.parse(rawUrl);
    String path = uri.getPath();
    if (path == null || !path.startsWith("/yt/")) {
      return false;
    }
    return uri.getQueryParameter("start") != null || uri.getQueryParameter("meta") != null;
  }

  /**
   * Serves a /yt/... request from the local .part file (query params start/len; query meta=1
   * resolves the stream and returns {size,mime,title} JSON). For the initiator this also triggers
   * downloading of missing ranges from googlevideo.
   */
  public static @Nullable WebResourceResponse serveVideoRange(
      int accountId, int msgId, String rawUrl, DcContext dcContext, Rpc rpc) {
    Uri uri = Uri.parse(rawUrl);
    String path = uri.getPath(); // "/yt/<videoId>.mp4"
    String videoId = path.substring("/yt/".length());
    int dot = videoId.lastIndexOf('.');
    if (dot >= 0) {
      videoId = videoId.substring(0, dot);
    }
    if (!YoutubeProtocol.isValidVideoId(videoId)) {
      return textResponse("bad video id", 400);
    }
    try {
      Instance inst = getInstance(accountId, msgId, dcContext, rpc, null);
      if (inst == null) {
        return textResponse("no instance", 500);
      }

      if (uri.getQueryParameter("meta") != null) {
        String meta = inst.p2pServer.resolveAndProbe(videoId);
        return textResponse(meta == null ? "{}" : meta, 200);
      }

      long start = Long.parseLong(uri.getQueryParameter("start"));
      int len = Integer.parseInt(uri.getQueryParameter("len"));
      if (len <= 0 || len > YoutubeProtocol.CHUNK_SIZE * YoutubeProtocol.PREFETCH_CHUNKS) {
        return textResponse("bad range", 400);
      }
      if (start < 0 || start + len > inst.p2pServer.getVideoSize(videoId)) {
        return textResponse("range out of bounds", 416);
      }

      // Missing ranges are downloaded (initiator) or waited for (viewer) before serving.
      if (!inst.p2pServer.hasVideo(videoId)) {
        // viewer: the range must already have arrived via realtime
        byte[] data = inst.viewerState.readBytes(videoId, start, len);
        if (data == null) {
          return textResponse("range not received yet", 404);
        }
        return bytesResponse(data, "video/mp4");
      }

      byte[] data = inst.p2pServer.ensureRange(videoId, start, len);
      if (data == null) {
        return textResponse("download failed", 502);
      }
      return bytesResponse(data, "video/mp4");
    } catch (Exception e) {
      Log.w(TAG, "serveVideoRange failed", e);
      return textResponse("error: " + e.getMessage(), 500);
    }
  }

  /** Small helper to build a text/plain WebResourceResponse. */
  private static WebResourceResponse textResponse(String text, int status) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Length", String.valueOf(bytes.length));
    return new WebResourceResponse(
        "text/plain",
        "UTF-8",
        status,
        status == 200 ? "OK" : "Error",
        headers,
        new ByteArrayInputStream(bytes));
  }

  /** Small helper to build a video/mp4 WebResourceResponse. */
  private static WebResourceResponse bytesResponse(byte[] data, String mime) {
    return new WebResourceResponse(mime, null, new ByteArrayInputStream(data));
  }

  /** Viewer-side persistent .part files, written from realtime frames, read back for playback. */
  public static final class YoutubeViewerState {
    private static final String TAG_VS = "YoutubeViewerState";

    private final Map<String, RandomAccessFile> partFiles = new ConcurrentHashMap<>();
    private final DcContext dcContext;

    YoutubeViewerState(DcContext dcContext) {
      this.dcContext = dcContext;
    }

    /** Persists a received frame at the given absolute offset. */
    public void writeChunk(String videoId, long start, String b64) {
      try {
        byte[] data = Base64.decode(b64, Base64.NO_WRAP);
        RandomAccessFile file = getPartFile(videoId);
        synchronized (file) {
          file.seek(start);
          file.write(data);
        }
      } catch (Exception e) {
        Log.w(TAG_VS, "writeChunk failed", e);
      }
    }

    /** Reads len bytes at absolute offset from the .part file; returns base64 or null. */
    public @Nullable String readChunk(String videoId, long start, int len) {
      try {
        byte[] data = readBytes(videoId, start, len);
        if (data == null) {
          return null;
        }
        return Base64.encodeToString(data, Base64.NO_WRAP);
      } catch (Exception e) {
        Log.w(TAG_VS, "readChunk failed", e);
        return null;
      }
    }

    /** Reads len bytes at absolute offset; returns null if fewer than len bytes exist. */
    public @Nullable byte[] readBytes(String videoId, long start, int len) {
      try {
        RandomAccessFile file = getPartFile(videoId);
        byte[] data = new byte[len];
        int off = 0;
        synchronized (file) {
          file.seek(start);
          while (off < len) {
            int n = file.read(data, off, len - off);
            if (n < 0) {
              break;
            }
            off += n;
          }
        }
        return off == len ? data : null;
      } catch (Exception e) {
        Log.w(TAG_VS, "readBytes failed", e);
        return null;
      }
    }

    public void closeAll() {
      for (RandomAccessFile f : partFiles.values()) {
        try {
          f.close();
        } catch (Exception e) {
          // ignore
        }
      }
      partFiles.clear();
    }

    private RandomAccessFile getPartFile(String videoId) throws Exception {
      RandomAccessFile f = partFiles.get(videoId);
      if (f == null) {
        synchronized (partFiles) {
          f = partFiles.get(videoId);
          if (f == null) {
            String path = DcHelper.getBlobdirFile(dcContext, "yt-" + videoId, ".part");
            f = new RandomAccessFile(path, "rw");
            partFiles.put(videoId, f);
          }
        }
      }
      return f;
    }
  }
}
