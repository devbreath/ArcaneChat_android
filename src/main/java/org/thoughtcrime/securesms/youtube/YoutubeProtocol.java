package org.thoughtcrime.securesms.youtube;

import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wire protocol spoken over the webxdc realtime channel between an initiator (which downloads from
 * googlevideo) and viewers (which receive chunks through the initiator).
 *
 * <p>All messages are JSON. Small control messages are sent as-is; binary chunk data is sent in
 * frames (see {@link #FRAME_SIZE}), each frame wrapped in a "cf" message.
 *
 * <p>Message types:
 *
 * <ul>
 *   <li>{@code {"t":"adv","videoId":"…","role":"viewer"|"initiator"}} – peer presence announcement
 *   <li>{@code {"t":"meta","videoId":"…","title":"…","mime":"…","size":N,"chunkSize":N,
 *       "frameSize":N,"framesPerChunk":N}} – video metadata, sent in response to "reqmeta"
 *   <li>{@code {"t":"reqmeta","videoId":"…"}} – ask for metadata
 *   <li>{@code {"t":"req","videoId":"…","start":N,"len":N}} – viewer requests a byte range
 *   <li>{@code {"t":"cf","videoId":"…","start":N,"seq":N,"total":N,"b64":"…"}} – chunk frame
 *   <li>{@code {"t":"eof","videoId":"…","start":N}} – chunk transfer completed
 *   <li>{@code {"t":"err","videoId":"…","start":N}} – a chunk failed on the initiator side
 * </ul>
 */
public final class YoutubeProtocol {
  public static final int CHUNK_SIZE = 512 * 1024; // 512 KB
  public static final int FRAME_SIZE = 16 * 1024; // 16 KB per realtime frame
  public static final int FRAMES_PER_CHUNK = CHUNK_SIZE / FRAME_SIZE; // 32
  public static final int PREFETCH_CHUNKS = 4;
  public static final int DOWNLOAD_ATTEMPTS = 3;
  public static final int DOWNLOAD_TIMEOUT_MS = 15000;
  public static final int BUFFER_WINDOW_SECONDS = 30;
  public static final String VIDEO_MIME = "video/mp4; codecs=\"avc1.64001f,mp4a.40.2\"";
  public static final String PLAYER_NAME = "YouTube Player";
  public static final String STATUS_KIND = "yt";

  public static final String TYPE_ADV = "adv";
  public static final String TYPE_META = "meta";
  public static final String TYPE_REQ_META = "reqmeta";
  public static final String TYPE_REQ = "req";
  public static final String TYPE_CHUNK_FRAME = "cf";
  public static final String TYPE_EOF = "eof";
  public static final String TYPE_ERR = "err";

  private YoutubeProtocol() {}

  /**
   * Finds the first youtube video URL anywhere in the given free-form text (share sheets typically
   * prepend a title). Returns the URL or null; the URL is only returned if a video id can be parsed
   * from it.
   */
  public static @Nullable String findVideoUrl(String text) {
    if (text == null) {
      return null;
    }
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "https?://(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)/\\S+")
            .matcher(text);
    while (m.find()) {
      String url = m.group();
      if (isVideoUrl(url)) {
        return url;
      }
    }
    return null;
  }

  /** True if the url points at a youtube watch/shorts/youtu.be video page. */
  public static boolean isVideoUrl(String url) {
    if (url == null) {
      return false;
    }
    String lower = url.toLowerCase(java.util.Locale.US);
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
      return false;
    }
    int schemeEnd = lower.indexOf("://") + 3;
    int slash = lower.indexOf('/', schemeEnd);
    String hostPart = slash < 0 ? lower.substring(schemeEnd) : lower.substring(schemeEnd, slash);
    String pathPart = slash < 0 ? "" : url.substring(slash);
    boolean hostOk =
        hostPart.equals("youtu.be")
            || hostPart.endsWith(".youtu.be")
            || hostPart.equals("youtube.com")
            || hostPart.endsWith(".youtube.com");
    if (!hostOk) {
      return false;
    }
    if (hostPart.endsWith("youtu.be")) {
      return pathPart.length() > 1 && isValidVideoId(pathPart.substring(1).split("[/?&#]")[0]);
    }
    return pathPart.contains("/watch")
        || pathPart.startsWith("/shorts/")
        || pathPart.startsWith("/embed/")
        || pathPart.startsWith("/live/")
        || pathPart.startsWith("/v/");
  }

  public static boolean isValidVideoId(String videoId) {
    if (videoId == null || videoId.length() != 11) {
      return false;
    }
    for (int i = 0; i < videoId.length(); i++) {
      char c = videoId.charAt(i);
      boolean ok =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  public static String buildAdv(String videoId, String role) {
    try {
      return new JSONObject()
          .put("t", TYPE_ADV)
          .put("videoId", videoId)
          .put("role", role)
          .toString();
    } catch (JSONException e) {
      return "{\"t\":\"" + TYPE_ADV + "\"}";
    }
  }

  public static String buildMeta(String videoId, String title, String mime, long size) {
    try {
      return new JSONObject()
          .put("t", TYPE_META)
          .put("videoId", videoId)
          .put("title", title)
          .put("mime", mime)
          .put("size", size)
          .put("chunkSize", CHUNK_SIZE)
          .put("frameSize", FRAME_SIZE)
          .put("framesPerChunk", FRAMES_PER_CHUNK)
          .toString();
    } catch (JSONException e) {
      return "{}";
    }
  }

  public static String buildReqMeta(String videoId) {
    try {
      return new JSONObject().put("t", TYPE_REQ_META).put("videoId", videoId).toString();
    } catch (JSONException e) {
      return "{}";
    }
  }

  public static String buildReq(String videoId, long start, int len) {
    try {
      return new JSONObject()
          .put("t", TYPE_REQ)
          .put("videoId", videoId)
          .put("start", start)
          .put("len", len)
          .toString();
    } catch (JSONException e) {
      return "{}";
    }
  }

  public static String buildChunkFrame(
      String videoId, long start, int seq, int total, byte[] data) {
    try {
      return new JSONObject()
          .put("t", TYPE_CHUNK_FRAME)
          .put("videoId", videoId)
          .put("start", start)
          .put("seq", seq)
          .put("total", total)
          .put("b64", java.util.Base64.getEncoder().encodeToString(data))
          .toString();
    } catch (JSONException e) {
      return "{}";
    }
  }

  public static String buildEof(String videoId, long start) {
    try {
      return new JSONObject()
          .put("t", TYPE_EOF)
          .put("videoId", videoId)
          .put("start", start)
          .toString();
    } catch (JSONException e) {
      return "{}";
    }
  }

  public static String buildErr(String videoId, long start) {
    try {
      return new JSONObject()
          .put("t", TYPE_ERR)
          .put("videoId", videoId)
          .put("start", start)
          .toString();
    } catch (JSONException e) {
      return "{}";
    }
  }

  public static class Message {
    public String type;
    public String videoId;
    public String role;
    public String title;
    public String mime;
    public String b64;
    public long start;
    public int len;
    public int seq;
    public int total;
    public long size;
    public int chunkSize;
    public int frameSize;
    public int framesPerChunk;
  }

  /** Parses a protocol message; returns null if it is not a valid youtube-protocol message. */
  public static @Nullable Message parse(String json) {
    try {
      JSONObject obj = new JSONObject(json);
      String type = obj.optString("t", "");
      switch (type) {
        case TYPE_ADV:
        case TYPE_META:
        case TYPE_REQ_META:
        case TYPE_REQ:
        case TYPE_CHUNK_FRAME:
        case TYPE_EOF:
        case TYPE_ERR:
          break;
        default:
          return null;
      }
      Message msg = new Message();
      msg.type = type;
      msg.videoId = obj.optString("videoId", "");
      msg.role = obj.optString("role", "");
      msg.title = obj.optString("title", "");
      msg.mime = obj.optString("mime", "");
      msg.b64 = obj.optString("b64", "");
      msg.start = obj.optLong("start", 0);
      msg.len = obj.optInt("len", 0);
      msg.seq = obj.optInt("seq", 0);
      msg.total = obj.optInt("total", 0);
      msg.size = obj.optLong("size", 0);
      msg.chunkSize = obj.optInt("chunkSize", CHUNK_SIZE);
      msg.frameSize = obj.optInt("frameSize", FRAME_SIZE);
      msg.framesPerChunk = obj.optInt("framesPerChunk", FRAMES_PER_CHUNK);
      return msg;
    } catch (JSONException e) {
      return null;
    }
  }
}
