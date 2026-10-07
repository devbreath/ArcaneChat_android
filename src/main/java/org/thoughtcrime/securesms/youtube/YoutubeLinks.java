package org.thoughtcrime.securesms.youtube;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * Detects youtube video links inside shared text and reuses the YoutubeShareActivity watch/share
 * dialog flow, so that sharing a link from another app's share sheet (ACTION_SEND with EXTRA_TEXT)
 * or tapping a youtube link inside a chat offers the in-app player instead of silently sending the
 * link as plain text.
 */
public final class YoutubeLinks {

  /** Finds the first youtube video URL in the given text. Returns the URL or null. */
  public static String findVideoUrl(String text) {
    return YoutubeProtocol.findVideoUrl(text);
  }

  /**
   * Shows the watch/share dialog for a youtube url, mirroring YoutubeShareActivity. Returns false
   * if the url is not a youtube video link (the caller should continue its normal flow).
   */
  public static boolean handleYouTubeUrl(Context context, String url) {
    android.util.Pair<String, String> parsed = YoutubeXdcInstaller.parseYouTubeUrl(url);
    if (parsed == null) {
      return false;
    }
    showShareDialog(context, parsed.first, parsed.second == null ? "" : parsed.second);
    return true;
  }

  /** Shows the Watch / Share dialog; "Watch" additionally opens the player in the picked chat. */
  public static void showShareDialog(Context context, String videoId, String title) {
    new AlertDialog.Builder(context)
        .setTitle(R.string.yt_dialog_title)
        .setMessage(videoId)
        .setPositiveButton(
            R.string.yt_watch_in_chat, (d, w) -> pickChatAndOpen(context, videoId, title, true))
        .setNegativeButton(
            R.string.yt_share_to_chat, (d, w) -> pickChatAndOpen(context, videoId, title, false))
        .setNeutralButton(R.string.cancel, null)
        .show();
  }

  private static void pickChatAndOpen(
      Context context, String videoId, String title, boolean openPlayer) {
    final DcContext dcContext = DcHelper.getContext(context);
    DcChatlist chatlist = dcContext.getChatlist(0, null, 0);
    int count = chatlist.getCnt();
    if (count == 0) {
      org.thoughtcrime.securesms.util.IntentUtils.showInBrowser(
          context, "https://www.youtube.com/watch?v=" + videoId);
      return;
    }
    String[] names = new String[count];
    final int[] chatIds = new int[count];
    for (int i = 0; i < count; i++) {
      names[i] = dcContext.getChat(chatlist.getChatId(i)).getName();
      chatIds[i] = chatlist.getChatId(i);
    }
    new AlertDialog.Builder(context)
        .setTitle(R.string.yt_pick_chat)
        .setItems(
            names,
            (d, w) -> {
              int chatId = chatIds[w];
              if (openPlayer) {
                YoutubeXdcInstaller.openPlayer(context, dcContext, chatId, videoId, title);
              } else {
                YoutubeXdcInstaller.shareToChat(context, dcContext, chatId, videoId, title);
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
}
