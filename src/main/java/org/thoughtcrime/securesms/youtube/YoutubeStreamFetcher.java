package org.thoughtcrime.securesms.youtube;

import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Direct HTTP client for googlevideo.com: resolves the real video URL + total size (via a
 * 1-byte-range probe) and downloads byte ranges into a sparse local .part file using
 * RandomAccessFile seeks. Does not use the core's get_http_response (no Range support there, and
 * base64 would bloat memory for 1-2 GB videos).
 */
public class YoutubeStreamFetcher {

  private static final String TAG = "YoutubeStreamFetcher";

  /** Information about a youtube video stream resolved from a player response. */
  public static class StreamInfo {
    public String url;
    public long size;
    public String mime;
    public String title;
  }

  /**
   * Resolves the direct media URL for a video without needing API keys: fetches the watch page,
   * extracts ytInitialPlayerResponse JSON, and picks the best muxed (progressive, format_id 18/22
   * style) stream URL. Google rotates stream URLs regularly, so this must be called close to when
   * the download actually happens.
   */
  public static StreamInfo resolveStream(String videoId) throws IOException {
    StreamInfo info = new StreamInfo();
    info.title = videoId;
    info.mime = "video/mp4";
    info.size = 0;
    info.url = null;

    String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
    HttpURLConnection conn = openConnection(watchUrl, null);
    try {
      int code = conn.getResponseCode();
      if (code != 200) {
        throw new IOException("watch page returned " + code);
      }
      String html = readAll(conn.getInputStream(), 4 * 1024 * 1024);
      info.url = extractStreamUrl(html);
      if (info.url == null) {
        throw new IOException("no muxed stream found in player response");
      }
      String title = extractTitle(html);
      if (title != null && !title.isEmpty()) {
        info.title = title;
      }
    } finally {
      conn.disconnect();
    }

    // Probe the media URL with a 1-byte range request to learn the total size from Content-Range.
    String mediaUrl = info.url;
    HttpURLConnection probe = openConnection(mediaUrl, "bytes=0-0");
    try {
      int code = probe.getResponseCode();
      if (code == 200) {
        // server ignored the range
        String len = probe.getHeaderField("Content-Length");
        if (len != null) {
          info.size = Long.parseLong(len);
        }
      } else if (code == 206) {
        String contentRange = probe.getHeaderField("Content-Range");
        if (contentRange != null) {
          int slash = contentRange.lastIndexOf('/');
          if (slash >= 0 && slash < contentRange.length() - 1) {
            info.size = Long.parseLong(contentRange.substring(slash + 1));
          }
        }
      } else {
        throw new IOException("probe returned " + code);
      }
    } finally {
      probe.disconnect();
    }
    return info;
  }

  /**
   * Fetches {@code len} bytes starting at {@code start} from the stream URL and appends them to the
   * sparse .part file at the same offset. Returns the number of bytes written.
   */
  public static long fetchRangeToFile(String url, long start, int len, RandomAccessFile out)
      throws IOException {
    HttpURLConnection conn = openConnection(url, "bytes=" + start + "-" + (start + len - 1));
    try {
      int code = conn.getResponseCode();
      if (code != 200 && code != 206) {
        throw new IOException("range request returned " + code);
      }
      InputStream in = conn.getInputStream();
      out.seek(start);
      byte[] buf = new byte[16384];
      long total = 0;
      try {
        while (total < len) {
          int n = in.read(buf, 0, (int) Math.min(buf.length, len - total));
          if (n < 0) {
            break;
          }
          out.write(buf, 0, n);
          total += n;
        }
      } finally {
        in.close();
      }
      return total;
    } finally {
      conn.disconnect();
    }
  }

  public static HttpURLConnection openConnection(String url, String range) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setConnectTimeout(YoutubeProtocol.DOWNLOAD_TIMEOUT_MS);
    conn.setReadTimeout(YoutubeProtocol.DOWNLOAD_TIMEOUT_MS);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 "
            + "Mobile Safari/537.36");
    conn.setRequestProperty("Referer", "https://www.youtube.com/");
    if (range != null) {
      conn.setRequestProperty("Range", range);
    }
    return conn;
  }

  private static String readAll(InputStream in, int maxBytes) throws IOException {
    StringBuilder sb = new StringBuilder();
    byte[] buf = new byte[16384];
    int n;
    while ((n = in.read(buf)) > 0 && sb.length() < maxBytes(sb, maxBytes)) {
      sb.append(new String(buf, 0, n, "UTF-8"));
    }
    in.close();
    return sb.toString();
  }

  private static int maxBytes(StringBuilder sb, int max) {
    return max - sb.length();
  }

  /**
   * Extracts a direct media URL from the watch page's ytInitialPlayerResponse. Looks for
   * progressive formats (itag 18 = 360p mp4 muxed, itag 22 = 720p muxed) since those can be
   * byte-served with plain Range requests without separate audio/video DASH adaptation.
   */
  static String extractStreamUrl(String html) {
    String marker = "ytInitialPlayerResponse = ";
    int idx = html.indexOf(marker);
    if (idx < 0) {
      marker = "ytInitialPlayerResponse:";
      idx = html.indexOf(marker);
    }
    if (idx < 0) {
      return null;
    }
    int jsonStart = idx + marker.length();
    // Find the end of the JSON object by brace matching (ignores strings, escapes).
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    int end = -1;
    for (int i = jsonStart; i < html.length(); i++) {
      char c = html.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else {
        if (c == '"') {
          inString = true;
        } else if (c == '{') {
          depth++;
        } else if (c == '}') {
          depth--;
          if (depth == 0) {
            end = i;
            break;
          }
        }
      }
    }
    if (end < 0) {
      return null;
    }
    String json = html.substring(jsonStart, end + 1);
    return pickStreamUrlFromPlayerResponse(json);
  }

  static String pickStreamUrlFromPlayerResponse(String json) {
    try {
      org.json.JSONObject root = new org.json.JSONObject(json);
      org.json.JSONArray formats = root.optJSONObject("streamingData").optJSONArray("formats");
      if (formats == null) {
        return null;
      }
      // Prefer itag 22 (720p muxed), then 18 (360p muxed): they are progressive mp4 with audio.
      for (int wantItag : new int[] {22, 18}) {
        for (int i = 0; i < formats.length(); i++) {
          org.json.JSONObject fmt = formats.getJSONObject(i);
          if (fmt.optInt("itag", 0) == wantItag) {
            String url = fmt.optString("url", "");
            if (!url.isEmpty()) {
              return url;
            }
          }
        }
      }
      // Fall back to any muxed format with a url and both audio+video quality present.
      for (int i = 0; i < formats.length(); i++) {
        org.json.JSONObject fmt = formats.getJSONObject(i);
        if (fmt.has("url") && fmt.has("audioQuality") && fmt.has("qualityLabel")) {
          return fmt.getString("url");
        }
      }
    } catch (org.json.JSONException e) {
      Log.w(TAG, "failed to parse player response", e);
    }
    return null;
  }

  private static String extractTitle(String html) {
    String marker = "<title>";
    int idx = html.indexOf(marker);
    if (idx < 0) {
      return null;
    }
    int start = idx + marker.length();
    int end = html.indexOf(" - YouTube", start);
    if (end < 0) {
      end = html.indexOf("</title>", start);
    }
    if (end < 0) {
      return null;
    }
    try {
      return java.net.URLDecoder.decode(
          html.substring(start, end)
              .replace("&#39;", "'")
              .replace("&quot;", "\"")
              .replace("&amp;", "&"),
          "UTF-8");
    } catch (Exception e) {
      return null;
    }
  }

  /** Extracts an 11-character youtube video id from a youtube URL, or null if not a video URL. */
  public static @androidx.annotation.Nullable String parseVideoId(String url) {
    if (url == null) {
      return null;
    }
    try {
      android.net.Uri uri = android.net.Uri.parse(url);
      String host = uri.getHost();
      if (host == null) {
        return null;
      }
      host = host.toLowerCase(Locale.US);
      if (host.endsWith("youtu.be")) {
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
          String id = path.substring(1);
          int slash = id.indexOf('/');
          if (slash > 0) {
            id = id.substring(0, slash);
          }
          return YoutubeProtocol.isValidVideoId(id) ? id : null;
        }
        return null;
      }
      if (host.endsWith("youtube.com")) {
        String v = uri.getQueryParameter("v");
        if (YoutubeProtocol.isValidVideoId(v)) {
          return v;
        }
        // /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
        String path = uri.getPath();
        if (path != null) {
          String[] segments = path.split("/");
          for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equals("shorts")
                || segments[i].equals("embed")
                || segments[i].equals("live")
                || segments[i].equals("v")) {
              String id = segments[i + 1];
              if (YoutubeProtocol.isValidVideoId(id)) {
                return id;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "failed to parse video id from " + url);
    }
    return null;
  }
}
