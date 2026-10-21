package org.thoughtcrime.securesms.youtube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YouTubeOgParserTest {

  @Test
  public void ogContent_propertyBeforeContent() {
    String html =
        "<html><head>"
            + "<meta property=\"og:title\" content=\"Some Title\">"
            + "<meta property=\"og:description\" content=\"Some description\">"
            + "<meta property=\"og:image\" content=\"https://i.ytimg.com/vi/abc/hqdefault.jpg\">"
            + "</head></html>";
    assertEquals("Some Title", YouTubeOgParser.ogContent(html, "title"));
    assertEquals("Some description", YouTubeOgParser.ogContent(html, "description"));
    assertEquals(
        "https://i.ytimg.com/vi/abc/hqdefault.jpg", YouTubeOgParser.ogContent(html, "image"));
  }

  @Test
  public void ogContent_contentBeforeProperty() {
    String html =
        "<meta content=\"Reversed Title\" property=\"og:title\">"
            + "<meta content=\"Reversed desc\" property=\"og:description\">";
    assertEquals("Reversed Title", YouTubeOgParser.ogContent(html, "title"));
    assertEquals("Reversed desc", YouTubeOgParser.ogContent(html, "description"));
  }

  @Test
  public void ogContent_singleQuotes() {
    String html = "<meta property='og:title' content='Single quoted'>";
    assertEquals("Single quoted", YouTubeOgParser.ogContent(html, "title"));
    String reversed = "<meta content='Single reversed' property='og:description'>";
    assertEquals("Single reversed", YouTubeOgParser.ogContent(reversed, "description"));
  }

  @Test
  public void ogContent_missingTag() {
    String html = "<html><head><meta property=\"og:title\" content=\"Only title\"></head></html>";
    assertNull(YouTubeOgParser.ogContent(html, "description"));
    assertNull(YouTubeOgParser.ogContent(html, "video"));
    assertNull(YouTubeOgParser.ogContent("", "title"));
    assertNull(YouTubeOgParser.ogContent(null, "title"));
    assertNull(YouTubeOgParser.ogContent(html, null));
  }

  @Test
  public void ogContent_unescapesEntities() {
    String html =
        "<meta property=\"og:description\" "
            + "content=\"Fish &amp; Chips &quot;quoted&quot; &#39;single&#39; &lt;tag&gt;\">";
    assertEquals(
        "Fish & Chips \"quoted\" 'single' <tag>", YouTubeOgParser.ogContent(html, "description"));
    String hex = "<meta property=\"og:title\" content=\"It&#x27;s here\">";
    assertEquals("It's here", YouTubeOgParser.ogContent(hex, "title"));
  }

  @Test
  public void previewSubtitle_prefersDescription() {
    YouTubePreview withDesc =
        new YouTubePreview("dQw4w9WgXcQ", "Title", "Channel", "Desc", "thumb", "watch");
    assertEquals("Desc", withDesc.getSubtitle());
    YouTubePreview noDesc =
        new YouTubePreview("dQw4w9WgXcQ", "Title", "Channel", null, "thumb", "watch");
    assertEquals("Channel", noDesc.getSubtitle());
    YouTubePreview emptyDesc =
        new YouTubePreview("dQw4w9WgXcQ", "Title", "Channel", "", "thumb", "watch");
    assertEquals("Channel", emptyDesc.getSubtitle());
    YouTubePreview neither =
        new YouTubePreview("dQw4w9WgXcQ", "Title", null, null, "thumb", "watch");
    assertNull(neither.getSubtitle());
  }
}
