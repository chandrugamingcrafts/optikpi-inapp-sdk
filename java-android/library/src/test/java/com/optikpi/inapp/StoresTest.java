package com.optikpi.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import com.optikpi.inapp.logic.PlayerTokenClaims;
import com.optikpi.inapp.logic.ShownStore;
import com.optikpi.inapp.render.BoundedBitmapCache;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class StoresTest {
  private final Context context = RuntimeEnvironment.getApplication();

  @Test
  public void shownStoreKeepsEveryIdThatNeverExpires() {
    // The server may keep returning a pop-up it has an impression for: forgetting the id would show
    // it again, so a pop-up with no expiry is remembered however many have been shown since.
    ShownStore store = new ShownStore(context);
    for (int i = 0; i < 1200; i++) {
      store.markShown("id-" + i, null);
    }
    Set<String> ids = store.load();
    assertEquals(1200, ids.size());
    assertTrue(ids.contains("id-0"));
    assertTrue(new ShownStore(context).load().contains("id-0"));
  }

  @Test
  public void shownStoreDropsAnIdOnceItsPopupHasExpired() {
    long[] now = {1_000_000L};
    ShownStore store = new ShownStore(context, () -> now[0]);
    store.markShown("short", Instant.ofEpochMilli(now[0] + 1_000));
    store.markShown("long", Instant.ofEpochMilli(now[0] + 100_000));
    store.markShown("forever", null);
    assertEquals(3, store.load().size());

    now[0] += 2_000;
    Set<String> ids = store.load();
    assertFalse(ids.contains("short"));
    assertTrue(ids.contains("long"));
    assertTrue(ids.contains("forever"));

    // What was dropped stays dropped after a restart, and the rest keeps its order.
    now[0] += 200_000;
    List<String> reloaded = new ArrayList<>(new ShownStore(context, () -> now[0]).load());
    assertEquals(List.of("forever"), reloaded);
  }

  @Test
  public void shownStoreReadsTheIdsAnOlderVersionStored() {
    context
        .getSharedPreferences("optikpi_inapp", Context.MODE_PRIVATE)
        .edit()
        .putString(ShownStore.KEY, "[\"a\",\"b\"]")
        .commit();
    ShownStore store = new ShownStore(context);
    assertEquals(List.of("a", "b"), new ArrayList<>(store.load()));
    store.markShown("c", null);
    assertEquals(List.of("a", "b", "c"), new ArrayList<>(new ShownStore(context).load()));
  }

  @Test
  public void shownStoreIgnoresAnId_alreadyThere() {
    ShownStore store = new ShownStore(context);
    store.markShown("a", null);
    store.markShown("a", null);
    assertEquals(1, store.load().size());
  }

  @Test
  public void imageCacheIsBoundedAndLeastRecentlyUsed() {
    BoundedBitmapCache cache = new BoundedBitmapCache();
    for (int i = 0; i < 8; i++) {
      cache.put("u" + i, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
    }
    assertEquals(8, cache.size());
    assertNotNull(cache.get("u0")); // u0 is now the most recently used
    cache.put("u8", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
    assertEquals(BoundedBitmapCache.DEFAULT_MAX_ENTRIES, cache.size());
    assertNull(cache.get("u1")); // the least recently used went
    assertNotNull(cache.get("u0"));
    assertNotNull(cache.get("u8"));
  }

  @Test
  public void aNullPlayerTokenIsUnreadable() {
    assertNull(PlayerTokenClaims.tryRead(null));
  }

  @Test
  public void imageCacheAlsoStaysUnderItsByteBudget() {
    // Each 100x100 ARGB bitmap is 40,000 bytes: room for two.
    BoundedBitmapCache cache = new BoundedBitmapCache(8, 90_000);
    for (int i = 0; i < 4; i++) {
      cache.put("u" + i, Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888));
    }
    assertEquals(2, cache.size());
    assertNull(cache.get("u0"));
    assertNotNull(cache.get("u3"));
  }

  @Test
  public void aBitmapBiggerThanTheWholeBudgetIsNotKept() {
    BoundedBitmapCache cache = new BoundedBitmapCache(8, 1_000);
    cache.put("big", Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888));
    assertEquals(0, cache.size());
  }
}
