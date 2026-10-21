package org.thoughtcrime.securesms.youtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YouTubeUrlParserTest {

  private static final String ID = "dQw4w9WgXcQ";
  private static final String WATCH = "https://www.youtube.com/watch?v=" + ID;

  @Test
  public void extractVideoId_watchUrls() {
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?v=" + ID));
    assertEquals(
        ID, YouTubeUrlParser.extractVideoId("https://m.youtube.com/watch?v=" + ID + "&t=10s"));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://music.youtube.com/watch?v=" + ID));
    assertEquals(
        ID,
        YouTubeUrlParser.extractVideoId(
            "https://www.youtube.com/watch?list=PL123&v=" + ID + "&index=2"));
  }

  @Test
  public void extractVideoId_shortForms() {
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID + "?t=42"));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID + "/"));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/shorts/" + ID));
    assertEquals(
        ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/shorts/" + ID + "?t=10"));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/embed/" + ID));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/live/" + ID));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/v/" + ID));
    assertEquals(
        ID, YouTubeUrlParser.extractVideoId("https://www.youtube-nocookie.com/embed/" + ID));
  }

  @Test
  public void extractVideoId_schemeHostAndIdVariants() {
    assertEquals(ID, YouTubeUrlParser.extractVideoId("http://www.youtube.com/watch?v=" + ID));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://WWW.YOUTUBE.COM/watch?v=" + ID));
    assertEquals(ID, YouTubeUrlParser.extractVideoId("https://YouTu.Be/" + ID));
    // ids may contain '-' and '_'
    assertEquals("aB-cD_eFg12", YouTubeUrlParser.extractVideoId("https://youtu.be/aB-cD_eFg12"));
    // 12+ chars is not a valid id
    assertNull(YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID + "X"));
    assertNull(YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?v=" + ID + "X"));
  }

  @Test
  public void extractVideoId_rejectsEvilHosts() {
    assertNull(YouTubeUrlParser.extractVideoId("https://youtube.com.evil.com/watch?v=" + ID));
    assertNull(YouTubeUrlParser.extractVideoId("https://youtu.be.evil.com/" + ID));
    assertNull(YouTubeUrlParser.extractVideoId("https://notyoutube.com/watch?v=" + ID));
    assertNull(YouTubeUrlParser.extractVideoId("https://fakeyoutu.be/" + ID));
  }

  @Test
  public void extractVideoId_rejectsNonVideoUrls() {
    assertNull(YouTubeUrlParser.extractVideoId(null));
    assertNull(YouTubeUrlParser.extractVideoId(""));
    assertNull(YouTubeUrlParser.extractVideoId("https://www.youtube.com/"));
    assertNull(YouTubeUrlParser.extractVideoId("https://www.youtube.com/feed/trending"));
    assertNull(YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?v=short"));
    assertNull(YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch"));
    assertNull(YouTubeUrlParser.extractVideoId("https://vimeo.com/12345678901"));
    assertNull(YouTubeUrlParser.extractVideoId("not a url"));
  }

  @Test
  public void extractFirstYouTubeUrl_fromMessageText() {
    assertEquals(
        WATCH,
        YouTubeUrlParser.extractFirstYouTubeUrl("check this out https://youtu.be/" + ID + " lol"));
    assertEquals(
        WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("https://www.youtube.com/shorts/" + ID));
    // first link wins
    assertEquals(
        WATCH,
        YouTubeUrlParser.extractFirstYouTubeUrl(WATCH + " and also https://youtu.be/AAAAAAAAAAA"));
    // trailing punctuation from chat text is stripped
    assertEquals(WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("watch: " + WATCH + "."));
    assertEquals(WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("(" + WATCH + ")"));
    assertEquals(WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("look " + WATCH + ", wow!"));
    assertEquals(WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("link: " + WATCH + "!?"));
    assertEquals(WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("\"" + WATCH + "\""));
    assertEquals(WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("[" + WATCH + "]"));
    // fragment timestamps are fine
    assertEquals(
        WATCH, YouTubeUrlParser.extractFirstYouTubeUrl("https://youtu.be/" + ID + "#t=1m30s"));
  }

  @Test
  public void extractFirstYouTubeUrl_noLink() {
    assertNull(YouTubeUrlParser.extractFirstYouTubeUrl(null));
    assertNull(YouTubeUrlParser.extractFirstYouTubeUrl(""));
    assertNull(YouTubeUrlParser.extractFirstYouTubeUrl("hello world, no links here"));
    assertNull(YouTubeUrlParser.extractFirstYouTubeUrl("see https://example.com/page for details"));
  }
}
