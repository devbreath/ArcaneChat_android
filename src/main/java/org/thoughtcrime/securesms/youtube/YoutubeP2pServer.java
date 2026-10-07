package org.thoughtcrime.securesms.youtube;

import android.util.Log;
import androidx.annotation.Nullable;
import chat.delta.rpc.Rpc;
import com.b44t.messenger.DcContext;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

/**
 * Runs on the initiator's device: serves byte ranges of a youtube video to viewers over the webxdc
 * realtime channel. Downloads missing ranges from googlevideo into a local sparse .part file
 * (chunk-wise, sequential, with retries), then streams them out as 16 KB frames.
 *
 * <p>One instance per webxdc instance message. Protocol messages arrive from WebxdcActivity's event
 * dispatch (main thread); heavy work is moved to background threads.
 */
public class YoutubeP2pServer {

  private static final String TAG = "YoutubeP2pServer";

  /** Sends a realtime protocol message to all peers of this webxdc instance. */
  public interface RealtimeSender {
    void send(String json);
  }

  private final int accountId;
  private final int instanceMsgId;
  private final DcContext dcContext;
  private final Rpc rpc;
  private final RealtimeSender sender;

  // State per videoId (several videos can be shared into the same webxdc instance).
  private final Map<String, VideoState> videos = new ConcurrentHashMap<>();

  // Serializes disk+network work so we stay at one download thread as planned.
  private final Object downloadLock = new Object();

  private static class VideoState {
    final String videoId;
    String title;
    String mime = "video/mp4";
    long size;
    String streamUrl;
    long streamUrlResolvedAt;
    String partFilePath;
    RandomAccessFile partFile;

    VideoState(String videoId) {
      this.videoId = videoId;
      this.title = videoId;
    }
  }

  public YoutubeP2pServer(
      int accountId, int instanceMsgId, DcContext dcContext, Rpc rpc, RealtimeSender sender) {
    this.accountId = accountId;
    this.instanceMsgId = instanceMsgId;
    this.dcContext = dcContext;
    this.rpc = rpc;
    this.sender = sender;
  }

  public int getInstanceMsgId() {
    return instanceMsgId;
  }

  public int getAccountId() {
    return accountId;
  }

  /** Entry point for incoming realtime protocol messages (already JSON-parsed upstream). */
  public void handleMessage(YoutubeProtocol.Message msg) {
    switch (msg.type) {
      case YoutubeProtocol.TYPE_REQ_META:
        handleReqMeta(msg);
        break;
      case YoutubeProtocol.TYPE_REQ:
        handleReq(msg);
        break;
      default:
        // adv/meta/cf/eof/err are not for the initiator-side server.
        break;
    }
  }

  /** True if this server tracks the given video (i.e. this device initiated it). */
  public boolean hasVideo(String videoId) {
    return videos.containsKey(videoId);
  }

  /**
   * Resolves the stream and probes its size if not done yet; returns the meta JSON for the player.
   * Blocking; call from a background thread.
   */
  public @Nullable String resolveAndProbe(String videoId) {
    try {
      VideoState st = trackVideo(videoId, null);
      ensureStreamUrl(st);
      return YoutubeProtocol.buildMeta(st.videoId, st.title, st.mime, st.size);
    } catch (Exception e) {
      Log.w(TAG, "resolveAndProbe failed for " + videoId, e);
      return null;
    }
  }

  /** Total size of a tracked video, or -1 if unknown. */
  public long getVideoSize(String videoId) {
    VideoState st = videos.get(videoId);
    return st == null ? -1 : st.size;
  }

  /**
   * Makes sure the range exists on disk (downloading missing chunks) and returns the bytes.
   * Blocking; call from a background thread.
   */
  public @Nullable byte[] ensureRange(String videoId, long start, int len) {
    try {
      VideoState st = videos.get(videoId);
      if (st == null) {
        return null;
      }
      ensureRangeOnDisk(st, start, len);
      return readRangeFromDisk(st, start, len);
    } catch (Exception e) {
      Log.w(TAG, "ensureRange failed for " + videoId + "@" + start, e);
      return null;
    }
  }

  private @Nullable byte[] readRangeFromDisk(VideoState st, long start, int len) throws Exception {
    RandomAccessFile file = getPartFile(st);
    try {
      byte[] data = new byte[len];
      int off = 0;
      synchronized (downloadLock) {
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
    } finally {
      closePartFile(st);
    }
  }

  private void handleReqMeta(YoutubeProtocol.Message msg) {
    final VideoState st = getState(msg.videoId);
    Util.runOnAnyBackgroundThread(
        () -> {
          try {
            ensureStreamUrl(st);
            sendJson(YoutubeProtocol.buildMeta(st.videoId, st.title, st.mime, st.size));
          } catch (Exception e) {
            Log.w(TAG, "resolve for meta failed", e);
            sendJson(YoutubeProtocol.buildErr(st.videoId, 0));
          }
        });
  }

  private void handleReq(YoutubeProtocol.Message msg) {
    final VideoState st = getState(msg.videoId);
    if (st == null) {
      sendJson(YoutubeProtocol.buildErr(msg.videoId, msg.start));
      return;
    }
    final long start = msg.start;
    final int len = msg.len;
    Util.runOnAnyBackgroundThread(
        () -> {
          try {
            ensureStreamUrl(st);
            serveRange(st, start, len);
          } catch (Exception e) {
            Log.w(TAG, "serveRange failed for " + st.videoId + "@" + start, e);
            sendJson(YoutubeProtocol.buildErr(st.videoId, start));
          }
        });
  }

  /** Returns the tracked state for a videoId, or null if we do not know this video. */
  private VideoState getState(String videoId) {
    return videos.get(videoId);
  }

  /** Registers a video that is being watched locally so viewers can request ranges. */
  public VideoState trackVideo(String videoId, String title) {
    VideoState st = videos.get(videoId);
    if (st != null) {
      if (title != null && !title.isEmpty()) {
        st.title = title;
      }
      return st;
    }
    st = new VideoState(videoId);
    if (title != null && !title.isEmpty()) {
      st.title = title;
    }
    videos.put(videoId, st);
    return st;
  }

  /** Resolves stream url + probes size; called from background threads only. */
  private synchronized void ensureStreamUrl(VideoState st) throws Exception {
    boolean urlExpired = System.currentTimeMillis() - st.streamUrlResolvedAt > URL_TTL_MS;
    if (st.streamUrl == null || urlExpired) {
      YoutubeStreamFetcher.StreamInfo info = YoutubeStreamFetcher.resolveStream(st.videoId);
      st.streamUrl = info.url;
      st.streamUrlResolvedAt = System.currentTimeMillis();
      st.size = info.size;
      st.title = info.title == null || info.title.isEmpty() ? st.title : info.title;
      st.mime = info.mime == null || info.mime.isEmpty() ? st.mime : info.mime;
      if (st.partFilePath == null) {
        st.partFilePath = DcHelper.getBlobdirFile(dcContext, "yt-" + st.videoId, ".part");
      }
    }
  }

  private void serveRange(VideoState st, long start, int len) throws Exception {
    if (st.size > 0 && start >= st.size) {
      sendJson(YoutubeProtocol.buildEof(st.videoId, start));
      return;
    }
    int len2 = len;
    if (st.size > 0 && start + len2 > st.size) {
      len2 = (int) (st.size - start);
    }
    ensureRangeOnDisk(st, start, len2);
    sendRangeAsFrames(st.videoId, start, len2);
  }

  /**
   * Makes sure the requested range exists in the .part file, downloading missing chunks (with
   * retries). The .part file is sparse: chunks are written at their absolute offsets, coverage is
   * tracked with a per-chunk presence map so nothing is downloaded twice.
   */
  private void ensureRangeOnDisk(VideoState st, long start, int len) throws Exception {
    synchronized (downloadLock) {
      RandomAccessFile file = getPartFile(st);
      try {
        long end = start + len;
        long pos = start;
        while (pos < end) {
          int chunkIdx = (int) (pos / YoutubeProtocol.CHUNK_SIZE);
          long chunkStart = (long) chunkIdx * YoutubeProtocol.CHUNK_SIZE;
          if (!hasChunk(st, chunkIdx)) {
            fetchChunk(st, chunkIdx, file);
          }
          pos = chunkStart + YoutubeProtocol.CHUNK_SIZE;
        }
      } finally {
        closePartFile(st);
      }
    }
  }

  private RandomAccessFile getPartFile(VideoState st) throws Exception {
    if (st.partFile == null) {
      st.partFile = new RandomAccessFile(st.partFilePath, "rw");
    }
    return st.partFile;
  }

  private void closePartFile(VideoState st) {
    try {
      if (st.partFile != null) {
        st.partFile.close();
      }
    } catch (Exception e) {
      Log.w(TAG, "close part file failed", e);
    } finally {
      st.partFile = null;
    }
  }

  // One bit of presence per 4 KB sub-chunk, kept per chunk index.
  private static final int SUB_CHUNK = 4096;
  private final Map<String, boolean[]> presenceMap = new ConcurrentHashMap<>();

  private boolean hasChunk(VideoState st, int chunkIdx) {
    boolean[] presence = presenceMap.get(st.videoId + ":" + chunkIdx);
    if (presence == null) {
      return false;
    }
    for (boolean b : presence) {
      if (!b) {
        return false;
      }
    }
    return true;
  }

  private void fetchChunk(VideoState st, int chunkIdx, RandomAccessFile file) throws Exception {
    long chunkStart = (long) chunkIdx * YoutubeProtocol.CHUNK_SIZE;
    int chunkLen = (int) Math.min(YoutubeProtocol.CHUNK_SIZE, st.size - chunkStart);
    if (chunkLen <= 0) {
      throw new Exception("chunk beyond end of stream");
    }
    Exception last = null;
    for (int attempt = 0; attempt < YoutubeProtocol.DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        long got = YoutubeStreamFetcher.fetchRangeToFile(st.streamUrl, chunkStart, chunkLen, file);
        if (got != chunkLen) {
          throw new Exception("short read: " + got + "/" + chunkLen);
        }
        markChunkPresent(st.videoId, chunkIdx, chunkLen);
        return;
      } catch (Exception e) {
        last = e;
        Log.w(TAG, "chunk " + chunkIdx + " download failed (attempt " + (attempt + 1) + ")", e);
        // stream URLs expire; re-resolve before retrying
        try {
          ensureStreamUrl(st);
        } catch (Exception e2) {
          Log.w(TAG, "re-resolve failed", e2);
        }
      }
    }
    throw last != null ? last : new Exception("chunk download failed");
  }

  private void markChunkPresent(String videoId, int chunkIdx, int chunkLen) {
    int subCount = (chunkLen + SUB_CHUNK - 1) / SUB_CHUNK;
    boolean[] presence = new boolean[subCount];
    java.util.Arrays.fill(presence, true);
    presenceMap.put(videoId + ":" + chunkIdx, presence);
  }

  /**
   * Sends the range as 16 KB realtime frames. Realtime delivery is ordered between peers in
   * practice; frames still carry seq so the viewer can detect gaps and re-request the range.
   */
  private void sendRangeAsFrames(String videoId, long start, int len) {
    VideoState st = videos.get(videoId);
    if (st == null) {
      return;
    }
    RandomAccessFile file = null;
    try {
      synchronized (downloadLock) {
        file = getPartFile(st);
        byte[] frame = new byte[YoutubeProtocol.FRAME_SIZE];
        int seq = 0;
        long pos = start;
        while (pos < start + len) {
          int toRead = (int) Math.min(YoutubeProtocol.FRAME_SIZE, start + len - pos);
          file.seek(pos);
          file.readFully(frame, 0, toRead);
          byte[] slice = new byte[toRead];
          System.arraycopy(frame, 0, slice, 0, toRead);
          seq++;
          sendJson(YoutubeProtocol.buildChunkFrame(videoId, start, seq, toRead, slice));
          pos += toRead;
        }
        sendJson(YoutubeProtocol.buildEof(videoId, start));
      }
    } catch (Exception e) {
      Log.w(TAG, "sendRangeAsFrames failed", e);
      sendJson(YoutubeProtocol.buildErr(videoId, start));
    } finally {
      if (st != null) {
        closePartFile(st);
      }
    }
  }

  private void sendJson(String json) {
    try {
      sender.send(json);
    } catch (Exception e) {
      Log.w(TAG, "send failed", e);
    }
  }

  /** Called when WebxdcActivity is destroyed; the .part file stays in the blobdir as a cache. */
  public void shutdown() {
    for (VideoState st : videos.values()) {
      closePartFile(st);
    }
  }

  private static final long URL_TTL_MS = 4 * 3600_000L;
}
