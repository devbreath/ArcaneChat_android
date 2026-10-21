package org.thoughtcrime.securesms.youtube;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts YouTube video ids and canonical watch urls from message text.
 *
 * <p>Pure Java, no Android dependencies, so it can be covered by plain JVM unit tests.
 */
public final class YouTubeUrlParser {

  private static final Pattern URL_CANDIDATE = Pattern.compile("https?://[^\\s<>\"'`)\\]]+");
  private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
  private static final String CANONICAL_WATCH_BASE = "https://www.youtube.com/watch?v=";

  private YouTubeUrlParser() {}

  /**
   * Finds the first YouTube video link in free text.
   *
   * @return canonical watch url ({@code https://www.youtube.com/watch?v=<id>}) or null.
   */
  public static String extractFirstYouTubeUrl(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    Matcher matcher = URL_CANDIDATE.matcher(text);
    while (matcher.find()) {
      String videoId = extractVideoId(matcher.group());
      if (videoId != null) {
        return CANONICAL_WATCH_BASE + videoId;
      }
    }
    return null;
  }

  /**
   * Extracts the 11-char video id from a single YouTube url. Supported forms: youtube.com/watch?v=,
   * m.youtube.com/watch, music.youtube.com/watch, youtu.be/&lt;id&gt;,
   * youtube.com/shorts/&lt;id&gt;, youtube.com/embed/&lt;id&gt;, youtube.com/live/&lt;id&gt;.
   *
   * @return video id or null when the url is not a recognized YouTube video link.
   */
  public static String extractVideoId(String url) {
    if (url == null || url.isEmpty()) {
      return null;
    }
    URI uri;
    try {
      uri = new URI(stripTrailingPunctuation(url.trim()));
    } catch (Exception e) {
      return null;
    }
    String host = uri.getHost();
    if (host == null) {
      return null;
    }
    host = host.toLowerCase(Locale.US);
    if (host.startsWith("www.")) {
      host = host.substring(4);
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    switch (host) {
      case "youtu.be":
        return validId(firstPathSegment(path));
      case "youtube.com":
      case "m.youtube.com":
      case "music.youtube.com":
      case "youtube-nocookie.com":
        if (path.equals("/watch") || path.equals("/watch/")) {
          return validId(queryParam(uri.getRawQuery(), "v"));
        }
        return validId(pathSegmentAfterPrefix(path));
      default:
        return null;
    }
  }

  private static String pathSegmentAfterPrefix(String path) {
    String[] segments = path.split("/");
    // segments[0] is empty (leading slash): /shorts/<id> -> ["", "shorts", "<id>"]
    if (segments.length >= 3) {
      String prefix = segments[1];
      if (prefix.equals("shorts")
          || prefix.equals("embed")
          || prefix.equals("live")
          || prefix.equals("v")) {
        return segments[2];
      }
    }
    return null;
  }

  private static String firstPathSegment(String path) {
    String[] segments = path.split("/");
    for (String segment : segments) {
      if (!segment.isEmpty()) {
        return segment;
      }
    }
    return null;
  }

  private static String queryParam(String rawQuery, String name) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return null;
    }
    for (String pair : rawQuery.split("&")) {
      int sep = pair.indexOf('=');
      if (sep > 0 && pair.substring(0, sep).equals(name)) {
        return pair.substring(sep + 1);
      }
    }
    return null;
  }

  private static String validId(String candidate) {
    if (candidate != null && VIDEO_ID.matcher(candidate).matches()) {
      return candidate;
    }
    return null;
  }

  private static String stripTrailingPunctuation(String url) {
    int end = url.length();
    while (end > 0 && ".,;:!?)}]'\"".indexOf(url.charAt(end - 1)) >= 0) {
      end--;
    }
    return url.substring(0, end);
  }
}
