package org.thoughtcrime.securesms.youtube;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Metadata shown in the YouTube link preview card below a chat message. */
public final class YouTubePreview {

  private final String videoId;
  private final String title;
  private final String authorName;
  private final String description;
  private final String thumbnailUrl;
  private final String watchUrl;

  public YouTubePreview(
      @NonNull String videoId,
      @Nullable String title,
      @Nullable String authorName,
      @Nullable String description,
      @Nullable String thumbnailUrl,
      @NonNull String watchUrl) {
    this.videoId = videoId;
    this.title = title;
    this.authorName = authorName;
    this.description = description;
    this.thumbnailUrl = thumbnailUrl;
    this.watchUrl = watchUrl;
  }

  public @NonNull String getVideoId() {
    return videoId;
  }

  public @Nullable String getTitle() {
    return title;
  }

  public @Nullable String getAuthorName() {
    return authorName;
  }

  public @Nullable String getDescription() {
    return description;
  }

  public @Nullable String getThumbnailUrl() {
    return thumbnailUrl;
  }

  public @NonNull String getWatchUrl() {
    return watchUrl;
  }

  /** Text shown under the title: description when available, channel name otherwise. */
  public @Nullable String getSubtitle() {
    if (description != null && !description.isEmpty()) {
      return description;
    }
    return authorName;
  }
}
