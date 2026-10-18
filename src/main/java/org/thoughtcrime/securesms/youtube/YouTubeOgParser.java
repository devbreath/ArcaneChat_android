package org.thoughtcrime.securesms.youtube;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts OpenGraph title/description/image from a watch-page HTML with a light regex. Pure Java,
 * no Android dependencies, so it can be covered by plain JVM unit tests.
 */
public final class YouTubeOgParser {

  private static final Pattern OG_TAG =
      Pattern.compile(
          "<meta[^>]+property=[\"']og:(title|description|image)[\"'][^>]+content=(\"([^\"]*)\"|'([^']*)')[^>]*>",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern OG_TAG_REVERSED =
      Pattern.compile(
          "<meta[^>]+content=(\"([^\"]*)\"|'([^']*)')[^>]+property=[\"']og:(title|description|image)[\"'][^>]*>",
          Pattern.CASE_INSENSITIVE);

  private YouTubeOgParser() {}

  /** Returns the og:title/og:description/og:image content, or null when absent. */
  public static String ogContent(String html, String property) {
    if (html == null || html.isEmpty() || property == null) {
      return null;
    }
    Matcher m = OG_TAG.matcher(html);
    while (m.find()) {
      if (m.group(1).equalsIgnoreCase(property)) {
        return unescape(firstNonNull(m.group(3), m.group(4)));
      }
    }
    Matcher reversed = OG_TAG_REVERSED.matcher(html);
    while (reversed.find()) {
      if (reversed.group(4).equalsIgnoreCase(property)) {
        return unescape(firstNonNull(reversed.group(2), reversed.group(3)));
      }
    }
    return null;
  }

  static String unescape(String value) {
    if (value == null) {
      return null;
    }
    return value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">");
  }

  private static String firstNonNull(String a, String b) {
    return a != null ? a : b;
  }
}
