package com.optikpi.inapp.source;

import static org.junit.Assert.assertEquals;

import com.optikpi.inapp.model.PopupFetchResult;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class HttpPopupSourceTest {
  @Test
  public void aNullBodyIsAnEmptyResultNotAFailure() throws Exception {
    PopupFetchResult result = HttpPopupSource.parseBody("null");
    assertEquals(0, result.popups.size());
    assertEquals(null, result.nextCheckAfterSec);
  }

  @Test
  public void aBodyThatIsNotAnObjectIsAnEmptyResult() throws Exception {
    assertEquals(0, HttpPopupSource.parseBody("[]").popups.size());
    assertEquals(0, HttpPopupSource.parseBody("\"x\"").popups.size());
  }

  @Test
  public void anObjectBodyIsParsed() throws Exception {
    PopupFetchResult result =
        HttpPopupSource.parseBody(
            "{\"popups\":[{\"id\":\"a\",\"trackingId\":\"t\",\"content\":{\"layout\":\"toast\"}}],"
                + "\"nextCheckAfterSec\":60}");
    assertEquals(1, result.popups.size());
    assertEquals(Integer.valueOf(60), result.nextCheckAfterSec);
  }

  @Test(expected = JSONException.class)
  public void textThatIsNotJsonStillFails() throws Exception {
    HttpPopupSource.parseBody("<html>");
  }

  @Test(expected = JSONException.class)
  public void anObjectFollowedByJunkStillFails() throws Exception {
    HttpPopupSource.parseBody("{} junk");
  }
}
