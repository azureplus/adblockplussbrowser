/*
 * This file is part of Adblock Plus <https://adblockplus.org/>,
 * Copyright (C) 2006-2016 Eyeo GmbH
 *
 * Adblock Plus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * Adblock Plus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Adblock Plus.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.adblockplus.sbrowser.contentblocker.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.adblockplus.adblockplussbrowser.R;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

public final class Engine
{
  private static final String TAG = Engine.class.getSimpleName();

  // TODO make use of this regex's
  public static final Pattern RE_SUBSCRIPTION_HEADER = Pattern.compile(
      "\\[Adblock(?:\\s*Plus\\s*([\\d\\.]+)?)?\\]", Pattern.CASE_INSENSITIVE);
  public static final Pattern RE_FILTER_META = Pattern.compile("^\\s*!\\s*(\\w+)\\s*:\\s*(.*)");
  public static final Pattern RE_FILTER_ELEMHIDE = Pattern
      .compile("^([^\\/\\*\\|\\@\"!]*?)#(\\@)?(?:([\\w\\-]+|\\*)((?:\\([\\w\\-]+(?:[$^*]?=[^\\(\\)\"]*)?\\))*)|#([^{}]+))$");
  public static final Pattern RE_FILTER_REGEXP = Pattern
      .compile("^(@@)?\\/.*\\/(?:\\$~?[\\w\\-]+(?:=[^,\\s]+)?(?:,~?[\\w\\-]+(?:=[^,\\s]+)?)*)?$");
  public static final Pattern RE_FILTER_OPTIONS = Pattern
      .compile("\\$(~?[\\w\\-]+(?:=[^,\\s]+)?(?:,~?[\\w\\-]+(?:=[^,\\s]+)?)*)$");
  public static final Pattern RE_FILTER_CSSPROPERTY = Pattern
      .compile("\\[\\-abp\\-properties=([\"'])([^\"']+)\\1\\]");

  public static final String USER_FILTERS_TITLE = "__filters";
  public static final String USER_EXCEPTIONS_TITLE = "__exceptions";

  public static final String ACTION_OPEN_SETTINGS = "com.samsung.android.sbrowser.contentBlocker.ACTION_SETTING";
  public static final String ACTION_UPDATE = "com.samsung.android.sbrowser.contentBlocker.ACTION_UPDATE";
  public static final String EASYLIST_URL = "https://easylist-downloads.adblockplus.org/easylist.txt";

  public static final String SUBSCRIPTIONS_EXCEPTIONSURL = "subscriptions_exceptionsurl";

  private static final String URL_ENCODE_CHARSET = "UTF-8";
  private static final String PREFS_KEY_PREVIOUS_VERSION = "key_previous_version";

  // The value below specifies an interval of [x, 2*x[, where x =
  // INITIAL_UPDATE_CHECK_DELAY_SECONDS
  private static final long INITIAL_UPDATE_CHECK_DELAY_SECONDS = 5;
  private static final long UPDATE_CHECK_INTERVAL_MINUTES = 30;
  private static final long BROADCAST_COMBINATION_DELAY_MILLIS = 2500;

  public static final long MILLIS_PER_SECOND = 1000;
  public static final long MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;
  public static final long MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE;
  public static final long MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR;

  private final ReentrantLock accessLock = new ReentrantLock();
  private DefaultSubscriptions defaultSubscriptions;
  private Subscriptions subscriptions;
  private JSONPrefs jsonPrefs;
  private AppInfo appInfo;
  private LinkedBlockingQueue<EngineEvent> engineEvents = new LinkedBlockingQueue<EngineEvent>();
  private Thread handlerThread;
  private Downloader downloader;
  private final Context serviceContext;
  private boolean wasFirstRun = false;
  private long nextUpdateBroadcast = Long.MAX_VALUE;

  private Engine(final Context context)
  {
    this.serviceContext = context;
  }

  public String getPrefsDefault(final String key)
  {
    return this.jsonPrefs.getDefaults(key);
  }

  DefaultSubscriptionInfo getDefaultSubscriptionInfo(final Subscription sub)
  {
    return this.defaultSubscriptions.getForUrl(sub.getURL());
  }

  void lock()
  {
    this.accessLock.lock();
  }

  void unlock()
  {
    this.accessLock.unlock();
  }

  public static boolean openSBrowserSettings(final Context activityContext)
  {
    final Intent intent = new Intent(ACTION_OPEN_SETTINGS);
    final List<ResolveInfo> list = activityContext.getPackageManager()
        .queryIntentActivities(intent, 0);
    if (list.size() > 0)
    {
      activityContext.startActivity(intent);
    }
    return list.size() > 0;
  }

  public static boolean hasCompatibleSBrowserInstalled(final Context activityContext)
  {
    try
    {
      return activityContext.getPackageManager()
          .queryIntentActivities(new Intent(ACTION_OPEN_SETTINGS), 0).size() > 0;
    }
    catch (final Throwable t)
    {
      return false;
    }
  }

  void requestUpdateBroadcast()
  {
    this.lock();
    try
    {
      this.nextUpdateBroadcast = System.currentTimeMillis() + BROADCAST_COMBINATION_DELAY_MILLIS;
    }
    finally
    {
      this.unlock();
    }
  }

  private void sendUpdateBroadcast()
  {
    runOnUiThread(new Runnable()
    {
      @Override
      public void run()
      {
        final Intent intent = new Intent();
        intent.setAction(ACTION_UPDATE);
        intent.setData(Uri.parse("package:" + Engine.this.serviceContext.getPackageName()));
        Engine.this.serviceContext.sendBroadcast(intent);
      }
    });
  }

  boolean canUseInternet()
  {
    final ConnectivityManager connManager = (ConnectivityManager) this.serviceContext
        .getSystemService(Context.CONNECTIVITY_SERVICE);
    final NetworkInfo current = connManager.getActiveNetworkInfo();
    if (current == null)
    {
      return false;
    }

    final SharedPreferences prefs = PreferenceManager
        .getDefaultSharedPreferences(this.serviceContext);
    final boolean wifiOnly = prefs.getString(
        this.serviceContext.getString(R.string.key_automatic_updates), "1").equals("1");

    if (wifiOnly)
    {
      if (current.isConnected() && !current.isRoaming())
      {
        switch (current.getType())
        {
          case ConnectivityManager.TYPE_BLUETOOTH:
          case ConnectivityManager.TYPE_ETHERNET:
          case ConnectivityManager.TYPE_WIFI:
          case ConnectivityManager.TYPE_WIMAX:
            return true;
          default:
            return false;
        }
      }
      return false;
    }
    return current.isConnected();
  }

  public List<SubscriptionInfo> getListedSubscriptions()
  {
    this.lock();
    try
    {
      return this.subscriptions.getSubscriptions(this);
    }
    finally
    {
      this.unlock();
    }
  }

  public void changeSubscriptionState(final String id, final boolean enabled)
  {
    this.engineEvents.add(new ChangeEnabledStateEvent(id, enabled));
  }

  void downloadFinished(final String id, final int responseCode, final String response,
      final Map<String, String> headers)
  {
    this.engineEvents.add(new DownloadFinishedEvent(id, responseCode, response, headers));
  }

  public File createAndWriteFile() throws IOException
  {
    this.lock();
    try
    {
      Log.d(TAG, "Writing filters...");
      return this.subscriptions.createAndWriteFile();
    }
    finally
    {
      this.unlock();
    }
  }

  public static void runOnUiThread(final Runnable runnable)
  {
    new Handler(Looper.getMainLooper()).post(runnable);
  }

  public boolean isAcceptableAdsEnabled()
  {
    this.lock();
    try
    {
      return this.subscriptions.isSubscriptionEnabled("url:"
          + this.getPrefsDefault(SUBSCRIPTIONS_EXCEPTIONSURL));
    }
    finally
    {
      this.unlock();
    }
  }

  public DefaultSubscriptionInfo getDefaultSubscriptionInfoForUrl(final String url)
  {
    return this.defaultSubscriptions.getForUrl(url);
  }

  public boolean wasFirstRun()
  {
    return this.wasFirstRun;
  }

  private void migrateFromPreviousVersion(final Context context)
  {
    try
    {
      final int versionCode = context.getPackageManager().getPackageInfo(context.getPackageName(),
          0).versionCode;
      final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
      int previous = prefs.getInt(PREFS_KEY_PREVIOUS_VERSION, 0);
      if (versionCode > previous)
      {
        if (previous > 0)
        {
          // We can do possible migration stuff here
          // Currently we only persist the new version code
        }
        prefs.edit().putInt(PREFS_KEY_PREVIOUS_VERSION, versionCode).commit();
      }
    }
    catch (final Throwable t)
    {
      Log.e(TAG, "Failed on migration, please clear all application data", t);
    }
  }

  static Engine create(final Context context) throws IOException
  {
    final Engine engine = new Engine(context);

    // Migration data from previous version (if needed)
    engine.migrateFromPreviousVersion(context);
    Log.d(TAG, "Migration done");

    engine.appInfo = AppInfo.create(context);

    Log.d(TAG, "Creating engine, appInfo=" + engine.appInfo.toString());

    final InputStream subscriptionsXml = context.getResources()
        .openRawResource(R.raw.subscriptions);
    try
    {
      engine.defaultSubscriptions = DefaultSubscriptions.fromStream(subscriptionsXml);
    }
    finally
    {
      subscriptionsXml.close();
    }

    Log.d(TAG, "Finished reading 'subscriptions.xml'");
    engine.subscriptions = Subscriptions.initialize(engine, context.getFilesDir(),
        context.getCacheDir());

    final InputStream prefsJson = context.getResources().openRawResource(R.raw.prefs);
    try
    {
      engine.jsonPrefs = JSONPrefs.create(prefsJson);
    }
    finally
    {
      prefsJson.close();
    }

    Log.d(TAG, "Finished reading JSON preferences");

    // Check if this is a fresh start, if so: initialize bundled easylist.
    engine.wasFirstRun = engine.subscriptions.wasUnitialized();
    if (engine.subscriptions.wasUnitialized())
    {
      Log.d(TAG, "Subscription storage was uninitialized, initializing...");

      final InputStream easylistTxt = context.getResources().openRawResource(R.raw.easylist);
      try
      {
        final Subscription easylist = engine.subscriptions.add(Subscription
            .create(EASYLIST_URL)
            .parseLines(readLines(easylistTxt)));
        easylist.putMeta(Subscription.KEY_UPDATE_TIMESTAMP, "0");
        easylist.setEnabled(true);
      }
      finally
      {
        easylistTxt.close();
      }
      Log.d(TAG, "Added and enabled bundled easylist");

      final InputStream exceptionsTxt = context.getResources()
          .openRawResource(R.raw.exceptionrules);
      try
      {
        final Subscription exceptions = engine.subscriptions.add(Subscription
            .create(engine.getPrefsDefault(SUBSCRIPTIONS_EXCEPTIONSURL))
            .parseLines(readLines(exceptionsTxt)));
        exceptions.putMeta(Subscription.KEY_UPDATE_TIMESTAMP, "0");
        exceptions.setEnabled(true);
      }
      finally
      {
        exceptionsTxt.close();
      }
      Log.d(TAG, "Added and enabled bundled exceptionslist");

      int additional = 0;
      for (final Subscription sub : engine.defaultSubscriptions.createSubscriptions())
      {
        if (!engine.subscriptions.hasSubscription(sub.getId()))
        {
          additional++;
          engine.subscriptions.add(sub);
        }
      }

      Log.d(TAG, "Added " + additional + " additional default/built-in subscriptions");
      engine.subscriptions.persistSubscriptions();
    }

    engine.handlerThread = new Thread(new EventHandler(engine));
    engine.handlerThread.setDaemon(true);
    engine.handlerThread.start();

    engine.downloader = Downloader.create(context, engine);

    return engine;
  }

  public static String readFileAsString(InputStream instream) throws IOException
  {
    final StringBuilder sb = new StringBuilder();
    final BufferedReader r = new BufferedReader(new InputStreamReader(instream, "UTF-8"));
    for (int ch = r.read(); ch != -1; ch = r.read())
    {
      sb.append((char) ch);
    }
    return sb.toString();
  }

  public static List<String> readLines(InputStream instream) throws IOException
  {
    final ArrayList<String> list = new ArrayList<String>();
    final BufferedReader r = new BufferedReader(new InputStreamReader(instream, "UTF-8"));
    for (String line = r.readLine(); line != null; line = r.readLine())
    {
      list.add(line);
    }
    return list;
  }

  URL createDownloadURL(final Subscription sub) throws IOException
  {
    final StringBuilder sb = new StringBuilder();

    sb.append(sub.getURL());
    if (sub.getURL().getQuery() != null)
    {
      sb.append('&');
    }
    else
    {
      sb.append('?');
    }

    sb.append("addonName=");
    sb.append(URLEncoder.encode(this.appInfo.addonName, URL_ENCODE_CHARSET));
    sb.append("&addonVersion=");
    sb.append(URLEncoder.encode(this.appInfo.addonVersion, URL_ENCODE_CHARSET));
    sb.append("&application=");
    sb.append(URLEncoder.encode(this.appInfo.application, URL_ENCODE_CHARSET));
    sb.append("&applicationVersion=");
    sb.append(URLEncoder.encode(this.appInfo.applicationVersion, URL_ENCODE_CHARSET));
    sb.append("&platform=");
    sb.append(URLEncoder.encode(this.appInfo.platform, URL_ENCODE_CHARSET));
    sb.append("&platformVersion=");
    sb.append(URLEncoder.encode(this.appInfo.platformVersion, URL_ENCODE_CHARSET));
    sb.append("&lastVersion=");
    sb.append(sub.getVersion());
    sb.append("&downloadCount=");
    final long downloadCount = sub.getDownloadCount();
    if (downloadCount < 5)
    {
      sb.append(downloadCount);
    }
    else
    {
      sb.append("4%2B"); // "4+" URL encoded
    }

    return new URL(sb.toString());
  }

  private static class EventHandler implements Runnable
  {
    private static final String TAG = EventHandler.class.getSimpleName();
    private final Engine engine;

    public EventHandler(final Engine engine)
    {
      this.engine = engine;
    }

    @Override
    public void run()
    {
      Log.d(TAG, "Handler thread started");
      boolean interrupted = false;
      long nextUpdateCheck = System.currentTimeMillis()
          + (long) ((1 + Math.random()) * INITIAL_UPDATE_CHECK_DELAY_SECONDS * MILLIS_PER_SECOND);
      while (!interrupted)
      {
        try
        {
          final EngineEvent event = this.engine.engineEvents.poll(100, TimeUnit.MILLISECONDS);
          engine.lock();
          try
          {
            if (event != null)
            {
              switch (event.getType())
              {
                case CHANGE_ENABLED_STATE:
                {
                  final ChangeEnabledStateEvent cese = (ChangeEnabledStateEvent) event;
                  Log.d(TAG, "Changing " + cese.id + " to enabled: " + cese.enabled);
                  engine.subscriptions.changeSubscriptionState(cese.id, cese.enabled);
                  break;
                }
                case DOWNLOAD_FINISHED:
                {
                  final DownloadFinishedEvent dfe = (DownloadFinishedEvent) event;
                  Log.d(TAG, "Download finished for '" + dfe.id + "' with response code "
                      + dfe.responseCode);
                  this.engine.subscriptions.updateSubscription(dfe.id, dfe.responseCode,
                      dfe.response, dfe.headers);
                  break;
                }
                default:
                  Log.d(TAG, "Unhandled type: " + event.getType());
                  break;
              }
            }

            final long currentTime = System.currentTimeMillis();
            if (currentTime > nextUpdateCheck)
            {
              nextUpdateCheck = currentTime + UPDATE_CHECK_INTERVAL_MINUTES * MILLIS_PER_MINUTE;

              this.engine.subscriptions.checkForUpdates();
            }

            if (currentTime > this.engine.nextUpdateBroadcast)
            {
              this.engine.nextUpdateBroadcast = Long.MAX_VALUE;
              Log.d(TAG, "Sending update broadcast");
              this.engine.sendUpdateBroadcast();
            }
          }
          finally
          {
            engine.unlock();
          }
        }
        catch (final InterruptedException e)
        {
          Log.d(TAG, "Handler interrupted", e);
          interrupted = true;
        }
        catch (final Throwable t)
        {
          Log.e(TAG, "Event processing failed: " + t.getMessage(), t);
        }
      }
      Log.d(TAG, "Handler thread finished");
    }
  }

  private static class EngineEvent
  {
    public enum EngineEventType
    {
      CHANGE_ENABLED_STATE,
      FORCE_DOWNLOAD,
      DOWNLOAD_FINISHED
    }

    private final EngineEventType type;

    protected EngineEvent(final EngineEventType type)
    {
      this.type = type;
    }

    public EngineEventType getType()
    {
      return this.type;
    }
  }

  private static class ChangeEnabledStateEvent extends EngineEvent
  {
    private final String id;
    private final boolean enabled;

    public ChangeEnabledStateEvent(final String id, final boolean enabled)
    {
      super(EngineEvent.EngineEventType.CHANGE_ENABLED_STATE);
      this.id = id;
      this.enabled = enabled;
    }
  }

  private static class DownloadFinishedEvent extends EngineEvent
  {
    private final String id;
    private final int responseCode;
    private final String response;
    private final HashMap<String, String> headers = new HashMap<String, String>();

    public DownloadFinishedEvent(final String id,
        final int responseCode,
        final String response,
        final Map<String, String> headers)
    {
      super(EngineEvent.EngineEventType.DOWNLOAD_FINISHED);
      this.id = id;
      this.responseCode = responseCode;
      this.response = response;
      if (headers != null)
      {
        this.headers.putAll(headers);
      }
    }
  }

  public void enqueueDownload(final Subscription sub, final boolean forced) throws IOException
  {
    if (sub.getURL() != null && sub.shouldUpdate(forced))
    {
      final HashMap<String, String> headers = new HashMap<String, String>();
      final String lastModified = sub.getMeta(Subscription.KEY_HTTP_LAST_MODIFIED);
      if (lastModified != null)
      {
        headers.put("If-Modified-Since", lastModified);
      }
      final String etag = sub.getMeta(Subscription.KEY_HTTP_ETAG);
      if (etag != null)
      {
        headers.put("If-None-Match", etag);
      }
      Log.d(TAG, headers.toString());
      this.downloader.enqueueDownload(this.createDownloadURL(sub), sub.getId(), headers);
    }
  }

  public void connectivityChanged()
  {
    this.downloader.connectivityChanged();
  }
}
