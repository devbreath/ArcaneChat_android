package org.thoughtcrime.securesms.youtube;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Loads YouTube video metadata without an API key: oEmbed for title/author/thumbnail plus a light
 * OpenGraph scrape for the description. Results are cached in memory and on disk (24h TTL).
 *
 * <p>All network I/O runs on a dedicated background executor; callbacks arrive on the main thread.
 * Failures (offline, invalid HTML, disabled setting) simply yield no card, never a crash.
 */
public final class YouTubePreviewFetcher {

  private static final String TAG = "YouTubePreviewFetcher";

  private static final String OEMBED_ENDPOINT = "https://www.youtube.com/oembed?url=";
  private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10) ArcaneChat";
  private static final int CONNECT_TIMEOUT_MS = 8000;
  private static final int READ_TIMEOUT_MS = 10000;
  private static final int MAX_HTML_BYTES = 512 * 1024;
  private static final long DISK_CACHE_TTL_MS = 24L * 60 * 60 * 1000;
  private static final long NEGATIVE_CACHE_TTL_MS = 10L * 60 * 1000;
  private static final int MAX_DESCRIPTION_LENGTH = 220;

  private static volatile YouTubePreviewFetcher instance;

  private final LruCache<String, CacheEntry> memoryCache = new LruCache<>(64);
  private final Map<String, List<PendingFetch>> inFlight = new HashMap<>();
  private final Executor executor = Executors.newFixedThreadPool(2);
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final File diskCacheDir;

  public interface Callback {
    void onPreviewLoaded(@NonNull YouTubePreview preview);

    void onPreviewFailed();
  }

  private static final class CacheEntry {
    final YouTubePreview preview;
    final long loadedAt;

    CacheEntry(YouTubePreview preview, long loadedAt) {
      this.preview = preview;
      this.loadedAt = loadedAt;
    }
  }

  private static final class PendingFetch {
    final String watchUrl;
    final Callback callback;

    PendingFetch(String watchUrl, Callback callback) {
      this.watchUrl = watchUrl;
      this.callback = callback;
    }
  }

  private YouTubePreviewFetcher(@NonNull Context context) {
    diskCacheDir = new File(context.getCacheDir(), "youtube_previews");
    // noinspection ResultOfMethodCallIgnored
    diskCacheDir.mkdirs();
  }

  public static YouTubePreviewFetcher getInstance(@NonNull Context context) {
    if (instance == null) {
      synchronized (YouTubePreviewFetcher.class) {
        if (instance == null) {
          instance = new YouTubePreviewFetcher(context.getApplicationContext());
        }
      }
    }
    return instance;
  }

  /**
   * Fetches preview metadata asynchronously. Must only be called for visible messages while the
   * setting is enabled; the caller checks both. Callback runs on the main thread.
   */
  public void fetch(@NonNull String videoId, @NonNull String watchUrl, @NonNull Callback callback) {
    CacheEntry cached;
    synchronized (memoryCache) {
      cached = memoryCache.get(videoId);
    }
    if (cached != null) {
      if (!isExpired(cached.loadedAt, cached.preview == null)) {
        postCached(callback, cached.preview);
        return;
      }
      synchronized (memoryCache) {
        memoryCache.remove(videoId);
      }
    }
    synchronized (inFlight) {
      List<PendingFetch> waiting = inFlight.get(videoId);
      if (waiting != null) {
        // Same video already loading for another visible item: attach and share the result.
        waiting.add(new PendingFetch(watchUrl, callback));
        return;
      }
      List<PendingFetch> pending = new ArrayList<>();
      pending.add(new PendingFetch(watchUrl, callback));
      inFlight.put(videoId, pending);
    }
    executor.execute(
        () -> {
          YouTubePreview preview = readDiskCache(videoId);
          if (preview == null) {
            try {
              preview = fetchFromNetwork(videoId, watchUrl);
            } catch (Exception e) {
              Log.w(TAG, "preview fetch failed for " + videoId);
            }
          }
          if (preview != null) {
            synchronized (memoryCache) {
              memoryCache.put(videoId, new CacheEntry(preview, System.currentTimeMillis()));
            }
            writeDiskCache(preview);
          } else {
            synchronized (memoryCache) {
              memoryCache.put(videoId, new CacheEntry(null, System.currentTimeMillis()));
            }
          }
          List<PendingFetch> pending;
          synchronized (inFlight) {
            pending = inFlight.remove(videoId);
          }
          if (pending == null) {
            return;
          }
          final YouTubePreview result = preview;
          mainHandler.post(
              () -> {
                for (PendingFetch fetch : pending) {
                  if (result != null) {
                    fetch.callback.onPreviewLoaded(result);
                  } else {
                    fetch.callback.onPreviewFailed();
                  }
                }
              });
        });
  }

  private void postCached(@NonNull Callback callback, @Nullable YouTubePreview preview) {
    mainHandler.post(
        () -> {
          if (preview != null) {
            callback.onPreviewLoaded(preview);
          } else {
            callback.onPreviewFailed();
          }
        });
  }

  private static boolean isExpired(long loadedAt, boolean negative) {
    long ttl = negative ? NEGATIVE_CACHE_TTL_MS : DISK_CACHE_TTL_MS;
    return System.currentTimeMillis() - loadedAt > ttl;
  }

  private @Nullable YouTubePreview fetchFromNetwork(
      @NonNull String videoId, @NonNull String watchUrl) {
    String title = null;
    String authorName = null;
    String thumbnailUrl = null;
    try {
      String oembedUrl = OEMBED_ENDPOINT + URLEncoder.encode(watchUrl, "UTF-8") + "&format=json";
      String json = downloadText(oembedUrl, 64 * 1024);
      if (json != null) {
        JSONObject obj = new JSONObject(json);
        title = optString(obj, "title");
        authorName = optString(obj, "author_name");
        thumbnailUrl = optString(obj, "thumbnail_url");
      }
    } catch (Exception e) {
      Log.w(TAG, "oEmbed failed for " + videoId);
    }

    String description = null;
    try {
      String html = downloadText(watchUrl, MAX_HTML_BYTES);
      if (html != null) {
        String ogTitle = ogContent(html, "title");
        String ogImage = ogContent(html, "image");
        description = ogContent(html, "description");
        if (title == null) {
          title = ogTitle;
        }
        if (thumbnailUrl == null) {
          thumbnailUrl = ogImage;
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "OG scrape failed for " + videoId);
    }

    if (thumbnailUrl == null || thumbnailUrl.isEmpty()) {
      thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }
    if ((title == null || title.isEmpty())
        && (authorName == null || authorName.isEmpty())
        && (description == null || description.isEmpty())) {
      return null;
    }
    return new YouTubePreview(
        videoId, title, authorName, trimDescription(description), thumbnailUrl, watchUrl);
  }

  private static @Nullable String optString(JSONObject obj, String key) {
    String value = obj.optString(key, null);
    return (value == null || value.isEmpty()) ? null : value;
  }

  private static @Nullable String trimDescription(@Nullable String description) {
    if (description == null) {
      return null;
    }
    String trimmed = description.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
      return trimmed.substring(0, MAX_DESCRIPTION_LENGTH) + "…";
    }
    return trimmed;
  }

  /** Extracts og:title/og:description/og:image with a light regex; no Jsoup needed. */
  static @Nullable String ogContent(@NonNull String html, @NonNull String property) {
    return YouTubeOgParser.ogContent(html, property);
  }

  private @Nullable String downloadText(@NonNull String urlString, int maxBytes) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(urlString);
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setRequestProperty("User-Agent", USER_AGENT);
      connection.setInstanceFollowRedirects(true);
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        return null;
      }
      try (InputStream in = connection.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
          total += read;
          if (total > maxBytes) {
            break;
          }
          out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
      }
    } catch (Exception e) {
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private @Nullable YouTubePreview readDiskCache(@NonNull String videoId) {
    File file = new File(diskCacheDir, videoId + ".json");
    if (!file.exists() || System.currentTimeMillis() - file.lastModified() > DISK_CACHE_TTL_MS) {
      return null;
    }
    try (FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      JSONObject obj = new JSONObject(out.toString(StandardCharsets.UTF_8));
      String watch = optString(obj, "watch");
      if (watch == null || YouTubeUrlParser.extractVideoId(watch) == null) {
        watch = "https://www.youtube.com/watch?v=" + videoId;
      }
      return new YouTubePreview(
          videoId,
          optString(obj, "title"),
          optString(obj, "author"),
          optString(obj, "description"),
          optString(obj, "thumbnail"),
          watch);
    } catch (Exception e) {
      return null;
    }
  }

  private void writeDiskCache(@NonNull YouTubePreview preview) {
    try {
      JSONObject obj = new JSONObject();
      obj.put("title", preview.getTitle());
      obj.put("author", preview.getAuthorName());
      obj.put("description", preview.getDescription());
      obj.put("thumbnail", preview.getThumbnailUrl());
      obj.put("watch", preview.getWatchUrl());
      try (FileOutputStream out =
          new FileOutputStream(new File(diskCacheDir, preview.getVideoId() + ".json"))) {
        out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      Log.w(TAG, "disk cache write failed");
    }
  }
}
