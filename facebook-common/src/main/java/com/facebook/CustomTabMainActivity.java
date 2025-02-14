/*
 * Copyright (c) 2014-present, Facebook, Inc. All rights reserved.
 *
 * You are hereby granted a non-exclusive, worldwide, royalty-free license to use,
 * copy, modify, and distribute this software in source code or binary form for use
 * in connection with the web services and APIs provided by Facebook.
 *
 * As with any software that integrates with the Facebook platform, your use of
 * this software is subject to the Facebook Developer Principles and Policies
 * [http://developers.facebook.com/policy/]. This copyright notice shall be
 * included in all copies or substantial portions of the software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.facebook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.facebook.internal.CustomTab;
import com.facebook.internal.InstagramCustomTab;
import com.facebook.internal.NativeProtocol;
import com.facebook.internal.Utility;
import com.facebook.internal.qualityvalidation.Excuse;
import com.facebook.internal.qualityvalidation.ExcusesForDesignViolations;
import com.facebook.login.LoginTargetApp;

@ExcusesForDesignViolations(@Excuse(type = "MISSING_UNIT_TEST", reason = "Legacy"))
public class CustomTabMainActivity extends Activity {
  public static final String EXTRA_ACTION =
      CustomTabMainActivity.class.getSimpleName() + ".extra_action";
  public static final String EXTRA_PARAMS =
      CustomTabMainActivity.class.getSimpleName() + ".extra_params";
  public static final String EXTRA_CHROME_PACKAGE =
      CustomTabMainActivity.class.getSimpleName() + ".extra_chromePackage";
  public static final String EXTRA_URL = CustomTabMainActivity.class.getSimpleName() + ".extra_url";
  public static final String EXTRA_TARGET_APP =
      CustomTabMainActivity.class.getSimpleName() + ".extra_targetApp";
  public static final String REFRESH_ACTION =
      CustomTabMainActivity.class.getSimpleName() + ".action_refresh";
  public static final String NO_ACTIVITY_EXCEPTION =
      CustomTabMainActivity.class.getSimpleName() + ".no_activity_exception";

  private boolean shouldCloseCustomTab = true;
  private BroadcastReceiver redirectReceiver;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Custom Tab Redirects should not be creating a new instance of this activity
    if (CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION.equals(getIntent().getAction())) {
      setResult(RESULT_CANCELED);
      finish();
      return;
    }

    if (savedInstanceState == null) {
      String action = getIntent().getStringExtra(EXTRA_ACTION);
      Bundle parameters = getIntent().getBundleExtra(EXTRA_PARAMS);
      String chromePackage = getIntent().getStringExtra(EXTRA_CHROME_PACKAGE);
      LoginTargetApp targetApp =
          LoginTargetApp.fromString(getIntent().getStringExtra(EXTRA_TARGET_APP));

      CustomTab customTab;
      switch (targetApp) {
        case INSTAGRAM:
          customTab = new InstagramCustomTab(action, parameters);
          break;
        default:
          customTab = new CustomTab(action, parameters);
      }

      boolean couldOpenCustomTab = customTab.openCustomTab(this, chromePackage);
      shouldCloseCustomTab = false;

      if (!couldOpenCustomTab) {
        setResult(RESULT_CANCELED, getIntent().putExtra(NO_ACTIVITY_EXCEPTION, true));
        finish();
        return;
      }

      // This activity will receive a broadcast if it can't be opened from the back stack
      redirectReceiver =
          new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
              // Remove the custom tab on top of this activity.
              Intent newIntent =
                  new Intent(CustomTabMainActivity.this, CustomTabMainActivity.class);
              newIntent.setAction(REFRESH_ACTION);
              newIntent.putExtra(EXTRA_URL, intent.getStringExtra(EXTRA_URL));
              newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
              startActivity(newIntent);
            }
          };
      LocalBroadcastManager.getInstance(this)
          .registerReceiver(
              redirectReceiver, new IntentFilter(CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION));
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    if (REFRESH_ACTION.equals(intent.getAction())) {
      // The custom tab is now destroyed so we can finish the redirect activity
      Intent broadcast = new Intent(CustomTabActivity.DESTROY_ACTION);
      LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
      sendResult(RESULT_OK, intent);
    } else if (CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION.equals(intent.getAction())) {
      // We have successfully redirected back to this activity. Return the result and close.
      sendResult(RESULT_OK, intent);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (shouldCloseCustomTab) {
      // The custom tab was closed without getting a result.
      sendResult(RESULT_CANCELED, null);
    }
    shouldCloseCustomTab = true;
  }

  private void sendResult(int resultCode, Intent resultIntent) {
    LocalBroadcastManager.getInstance(this).unregisterReceiver(redirectReceiver);
    if (resultIntent != null) {
      String responseURL = resultIntent.getStringExtra(EXTRA_URL);
      Bundle results = responseURL != null ? parseResponseUri(responseURL) : new Bundle();
      Intent nativeProtocolResultIntent =
          NativeProtocol.createProtocolResultIntent(getIntent(), results, null);

      setResult(
          resultCode,
          nativeProtocolResultIntent != null ? nativeProtocolResultIntent : resultIntent);
    } else {
      setResult(resultCode, NativeProtocol.createProtocolResultIntent(getIntent(), null, null));
    }
    finish();
  }

  private static Bundle parseResponseUri(String urlString) {
    Uri u = Uri.parse(urlString);
    Bundle b = Utility.parseUrlQueryString(u.getQuery());
    b.putAll(Utility.parseUrlQueryString(u.getFragment()));
    return b;
  }
}
