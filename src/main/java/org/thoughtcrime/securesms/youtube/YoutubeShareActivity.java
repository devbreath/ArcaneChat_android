package org.thoughtcrime.securesms.youtube;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;
import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * Opened when the user taps a youtube.com/watch or youtu.be link in an external app (browser).
 * Offers to watch the video inside ArcaneChat's built-in YouTube Player webxdc and/or share it to a
 * chat as a watch-invite.
 *
 * <p>NOTE: registering for https youtube.com links coexists with the system browser and other
 * app-link handlers; Android shows the standard "Open with" chooser unless the user sets a default.
 * We do not use autoVerify because we cannot host assetlinks for youtube.com.
 */
public class YoutubeShareActivity extends BaseActionBarActivity {

  private static final String TAG = "YoutubeShareActivity";

  static final String EXTRA_VIDEO_ID = "videoId";
  static final String EXTRA_TITLE = "title";
  static final String EXTRA_CHAT_ID = "chatId";
  static final String EXTRA_OPEN_PLAYER = "openPlayer";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    String url = resolveUrl(getIntent());
    if (url == null) {
      finish();
      return;
    }
    final android.util.Pair<String, String> parsed = YoutubeXdcInstaller.parseYouTubeUrl(url);
    if (parsed == null) {
      Log.w(TAG, "not a youtube video url: " + url);
      openExternally(url);
      return;
    }
    showOptionsDialog(url, parsed.first, parsed.second == null ? "" : parsed.second);
  }

  private String resolveUrl(Intent intent) {
    if (intent == null) {
      return null;
    }
    if (Intent.ACTION_VIEW.equals(intent.getAction())) {
      Uri data = intent.getData();
      if (data != null) {
        return data.toString();
      }
    }
    return intent.getStringExtra(EXTRA_URL);
  }

  private static final String EXTRA_URL = "url";

  private void showOptionsDialog(String url, String videoId, String title) {
    final DcContext dcContext = DcHelper.getContext(this);
    new AlertDialog.Builder(this)
        .setTitle(R.string.yt_dialog_title)
        .setMessage(videoId + (title.isEmpty() ? "" : " — " + title))
        .setPositiveButton(
            R.string.yt_watch_in_chat, (d, w) -> pickChatAndOpen(videoId, title, true))
        .setNegativeButton(
            R.string.yt_share_to_chat, (d, w) -> pickChatAndOpen(videoId, title, false))
        .setNeutralButton(R.string.cancel, null)
        .show();
  }

  private void pickChatAndOpen(String videoId, String title, boolean openPlayer) {
    final DcContext dcContext = DcHelper.getContext(this);
    DcChatlist chatlist = dcContext.getChatlist(0, null, 0);
    int count = chatlist.getCnt();
    if (count == 0) {
      openExternally("https://www.youtube.com/watch?v=" + videoId);
      return;
    }
    String[] names = new String[count];
    final int[] chatIds = new int[count];
    for (int i = 0; i < count; i++) {
      names[i] = dcContext.getChat(chatlist.getChatId(i)).getName();
      chatIds[i] = chatlist.getChatId(i);
    }
    new AlertDialog.Builder(this)
        .setTitle(R.string.yt_pick_chat)
        .setItems(
            names,
            (d, w) -> {
              int chatId = chatIds[w];
              if (openPlayer) {
                YoutubeXdcInstaller.openPlayer(this, dcContext, chatId, videoId, title);
              } else {
                YoutubeXdcInstaller.shareToChat(this, dcContext, chatId, videoId, title);
              }
              finish();
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void openExternally(String url) {
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (Exception e) {
      Log.w(TAG, "cannot open externally", e);
    }
    finish();
  }
}
