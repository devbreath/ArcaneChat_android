package org.thoughtcrime.securesms.util;

import android.content.Context;
import android.preference.PreferenceManager;
import androidx.annotation.NonNull;

/**
 * Fork-only: remembers the last visible message per (accountId, chatId), so reopening a chat
 * restores the reading position instead of jumping to the bottom or the last-seen marker.
 */
public class ChatPositionMemory {

  private static final String KEY_PREFIX = "chat_last_visible_msg_";

  private static String key(int accountId, long chatId) {
    return KEY_PREFIX + accountId + "_" + chatId;
  }

  public static int getLastVisibleMsgId(@NonNull Context context, int accountId, long chatId) {
    return PreferenceManager.getDefaultSharedPreferences(context).getInt(key(accountId, chatId), 0);
  }

  public static void setLastVisibleMsgId(
      @NonNull Context context, int accountId, long chatId, int msgId) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putInt(key(accountId, chatId), msgId)
        .apply();
  }

  public static void clearLastVisibleMsgId(@NonNull Context context, int accountId, long chatId) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .remove(key(accountId, chatId))
        .apply();
  }
}
