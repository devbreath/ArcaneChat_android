package org.thoughtcrime.securesms.youtube;

import android.content.Context;
import android.content.res.AssetManager;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.WebxdcActivity;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

/**
 * Makes sure a "YouTube Player" webxdc instance exists in a chat: generates youtube.xdc from
 * packaged asset files on first use and sends it to the chat as a DC_MSG_WEBXDC. Also builds the
 * status-update payload used to invite peers to a video.
 */
public final class YoutubeXdcInstaller {

  private static final String CONFIG_YT_VERSION = "ui.youtube_version";
  private static final int YT_VERSION = 1;

  private YoutubeXdcInstaller() {}

  /**
   * Finds an existing "YouTube Player" webxdc instance in the chat, or creates one by sending the
   * generated youtube.xdc into the chat. Returns the instance message id or 0 on failure.
   */
  public static int ensureInstance(Context context, DcContext dcContext, int chatId) {
    int[] msgs = dcContext.getChatMsgs(chatId, 0, 0);
    for (int i = msgs.length - 1; i >= 0; i--) {
      DcMsg msg = dcContext.getMsg(msgs[i]);
      if (isYouTubeInstance(dcContext, msg)) {
        return msg.getId();
      }
    }
    try {
      String xdcPath = generateXdcIfNeeded(context, dcContext);
      DcMsg msg = new DcMsg(dcContext, DcMsg.DC_MSG_WEBXDC);
      msg.setFileAndDeduplicate(xdcPath, "youtube.xdc", "application/octet-stream");
      int msgId = dcContext.sendMsg(chatId, msg);
      return msgId;
    } catch (Exception e) {
      e.printStackTrace();
      return 0;
    }
  }

  public static boolean isYouTubeInstance(DcContext dcContext, DcMsg msg) {
    if (msg == null || !msg.isOk() || msg.getType() != DcMsg.DC_MSG_WEBXDC) {
      return false;
    }
    byte[] blob = msg.getWebxdcBlob("manifest.toml");
    if (blob == null) {
      return false;
    }
    return new String(blob, java.nio.charset.StandardCharsets.UTF_8)
        .contains("name = \"" + YoutubeProtocol.PLAYER_NAME + "\"");
  }

  /**
   * Generates youtube.xdc (a zip) in the account blobdir from src/main/assets/webxdc/youtube/
   * contents, unless a current version is already present.
   */
  public static String generateXdcIfNeeded(Context context, DcContext dcContext)
      throws IOException {
    if (dcContext.getConfigInt(CONFIG_YT_VERSION) >= YT_VERSION) {
      String existing = dcContext.getBlobdir() + "/youtube.xdc";
      if (new File(existing).exists()) {
        return existing;
      }
    }
    String outPath = DcHelper.getBlobdirFile(dcContext, "youtube", ".xdc");
    writeXdc(context, dcContext, outPath);
    dcContext.setConfigInt(CONFIG_YT_VERSION, YT_VERSION);
    return outPath;
  }

  /** Writes youtube.xdc (a zip) from src/main/assets/webxdc/youtube/ contents. */
  public static void writeXdc(Context context, DcContext dcContext, String outPath)
      throws IOException {
    File tmp = new File(outPath + ".tmp");
    ZipOutputStream zip = new ZipOutputStream(new java.io.FileOutputStream(tmp));
    try {
      putEntry(zip, "manifest.toml", buildManifest().getBytes("UTF-8"));
      putEntry(zip, "index.html", readAsset(context.getAssets(), "webxdc/youtube/index.html"));
      putEntry(zip, "player.js", readAsset(context.getAssets(), "webxdc/youtube/player.js"));
      putEntry(zip, "icon.png", readAsset(context.getAssets(), "webxdc/youtube/icon.png"));
    } finally {
      zip.close();
    }
    if (!tmp.renameTo(new File(outPath))) {
      throw new IOException("could not rename " + tmp + " to " + outPath);
    }
  }

  private static byte[] readAsset(AssetManager assets, String path) throws IOException {
    InputStream in = assets.open(path);
    try {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      copy(in, out);
      return out.toByteArray();
    } finally {
      in.close();
    }
  }

  private static void putEntry(ZipOutputStream zip, String entryName, byte[] content)
      throws IOException {
    zip.putNextEntry(new ZipEntry(entryName));
    zip.write(content);
    zip.closeEntry();
  }

  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[16384];
    int n;
    while ((n = in.read(buf)) > 0) {
      out.write(buf, 0, n);
    }
  }

  private static String buildManifest() {
    return "name = \"YouTube Player\"\n"
        + "source_code_url = \"https://github.com/deltachat/ArcaneChat_android\"\n"
        + "request_internet_access = false\n";
  }

  /** Builds the status-update payload that invites peers to watch a video. */
  public static String buildStatusUpdateJson(String videoId, String title, String fromAddr) {
    try {
      return new JSONObject()
          .put("kind", YoutubeProtocol.STATUS_KIND)
          .put("videoId", videoId)
          .put("title", title)
          .put("from", fromAddr)
          .toString();
    } catch (JSONException e) {
      return "{}";
    }
  }

  /** Ensures the player instance exists and sends the invite payload into the chat's app. */
  public static void shareToChat(
      Context context, DcContext dcContext, int chatId, String videoId, String title) {
    Util.runOnAnyBackgroundThread(
        () -> {
          int msgId = ensureInstance(context, dcContext, chatId);
          if (msgId == 0) {
            Util.runOnMain(
                () ->
                    android.widget.Toast.makeText(
                            context, R.string.error, android.widget.Toast.LENGTH_LONG)
                        .show());
            return;
          }
          String payload =
              buildStatusUpdateJson(videoId, title, dcContext.getConfig("configured_addr"));
          dcContext.sendWebxdcStatusUpdate(msgId, payload);
        });
  }

  /** Ensures the player instance exists, pushes the payload, and opens the player activity. */
  public static void openPlayer(
      Context context, DcContext dcContext, int chatId, String videoId, String title) {
    Util.runOnAnyBackgroundThread(
        () -> {
          int msgId = ensureInstance(context, dcContext, chatId);
          if (msgId == 0) {
            Util.runOnMain(
                () ->
                    android.widget.Toast.makeText(
                            context, R.string.error, android.widget.Toast.LENGTH_LONG)
                        .show());
            return;
          }
          String payload =
              buildStatusUpdateJson(videoId, title, dcContext.getConfig("configured_addr"));
          dcContext.sendWebxdcStatusUpdate(msgId, payload);
          Util.runOnMain(
              () -> WebxdcActivity.openWebxdcActivity(context, msgId, chatId, false, ""));
        });
  }

  /**
   * Parses a video id from a youtube url; the returned pair is (videoId, ""), title is resolved
   * lazily by the player.
   */
  public static android.util.Pair<String, String> parseYouTubeUrl(String url) {
    String videoId = YoutubeStreamFetcher.parseVideoId(url);
    if (videoId == null) {
      return null;
    }
    return new android.util.Pair<>(videoId, "");
  }
}
