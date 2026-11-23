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
  public void shownStoreDropsTheOldestIdsFirst() {
    ShownStore store = new ShownStore(context);
    for (int i = 0; i < ShownStore.MAX_IDS + 3; i++) {
      store.markShown("id-" + i);
    }
    Set<String> ids = store.load();
    assertEquals(ShownStore.MAX_IDS, ids.size());
    assertFalse(ids.contains("id-0"));
    assertFalse(ids.contains("id-2"));
    assertTrue(ids.contains("id-3"));
    // The id just shown is never the one that goes.
    assertTrue(ids.contains("id-" + (ShownStore.MAX_IDS + 2)));
  }

  @Test
  public void shownStoreKeepsItsOrderAcrossARestart() {
    ShownStore first = new ShownStore(context);
    for (int i = 0; i < ShownStore.MAX_IDS + 3; i++) {
      first.markShown("id-" + i);
    }
    List<String> reloaded = new ArrayList<>(new ShownStore(context).load());
    assertEquals(ShownStore.MAX_IDS, reloaded.size());
    assertEquals("id-3", reloaded.get(0));
    assertEquals("id-" + (ShownStore.MAX_IDS + 2), reloaded.get(reloaded.size() - 1));

    // Past the cap again after the restart: the oldest still go first.
    ShownStore second = new ShownStore(context);
    second.markShown("newest");
    assertFalse(second.load().contains("id-3"));
    assertTrue(second.load().contains("newest"));
  }

  @Test
  public void shownStoreIgnoresAnId_alreadyThere() {
    ShownStore store = new ShownStore(context);
    store.markShown("a");
    store.markShown("a");
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
