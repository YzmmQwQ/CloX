package com.yzmm.clox.update;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.yzmm.clox.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * OTAManager3 - Universal Over-The-Air Update Manager for Android Applications
 *
 * <h2>What's new in this version</h2>
 * <ul>
 *   <li><b>Callback / listener support</b> - the host app can handle all UI itself
 *       (see {@link Callback} / {@link SimpleCallback}). If no listener is set the
 *       manager falls back to its own built-in dialogs, so old "hands-off" usage keeps
 *       working unchanged.</li>
 *   <li><b>Three download modes</b>, selectable at runtime or from the UI:
 *       {@link DownloadMode#DOWNLOAD_MODE_BROWSER browser},
 *       {@link DownloadMode#DOWNLOAD_MODE_IN_APP DownloadManager}, and
 *       {@link DownloadMode#DOWNLOAD_MODE_DIRECT in-app direct download with progress}.
 *       Use {@link DownloadMode#DOWNLOAD_MODE_ASK} to let the user pick.</li>
 *   <li><b>Full changelog history</b> - fetches every release, not just the latest, and
 *       builds a combined "what's new since your version" changelog
 *       (see {@link UpdateInfo#allVersions} and {@link UpdateInfo#changelogSinceCurrent}).</li>
 *   <li><b>GitLab and GitHub</b> release providers ({@link Provider}).</li>
 *   <li><b>Runtime configuration</b> via fluent setters, with the old static constants
 *       kept as defaults. 100% backward compatible.</li>
 * </ul>
 *
 * <h2>Backward-compatible (hands-off) usage — unchanged</h2>
 * <pre>
 *    OTAManager3 ota = new OTAManager3(context);
 *    ota.checkUpdate(false); // WiFi-only, built-in dialog, DownloadManager install
 *    ota.checkUpdate(true);  // force check on any connection
 * </pre>
 *
 * <h2>Versatile usage — host app controls everything</h2>
 * <pre>
 *    OTAManager3 ota = new OTAManager3(context)
 *        .setProvider(OTAManager3.Provider.GITHUB)
 *        .setRepo("myuser", "myapp")
 *        .setToken("ghp_xxx")                 // optional, for private repos
 *        .setDownloadMode(OTAManager3.DownloadMode.DOWNLOAD_MODE_DIRECT)
 *        .setWifiOnly(false)
 *        .setListener(new OTAManager3.SimpleCallback() {
 *            &#64;Override public void onUpdateAvailable(OTAManager3.UpdateInfo info) {
 *                // show YOUR OWN dialog using info.latestVersion / info.changelogSinceCurrent
 *                // then, when the user taps "update", pick any mode:
 *                ota.download(info, OTAManager3.DownloadMode.DOWNLOAD_MODE_DIRECT);
 *            }
 *            &#64;Override public void onDownloadProgress(int percent, long done, long total) {
 *                progressBar.setProgress(percent);
 *            }
 *            &#64;Override public void onUpToDate(OTAManager3.UpdateInfo info) { }
 *            &#64;Override public void onError(OTAManager3.OTAError e, String msg, Exception ex) { }
 *        });
 *    ota.checkUpdate(true);
 * </pre>
 *
 * <h2>Requirements</h2>
 * <pre>
 * 1. AndroidManifest.xml permissions:
 *    &lt;uses-permission android:name="android.permission.INTERNET" /&gt;
 *    &lt;uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /&gt;
 *    &lt;uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" /&gt;
 *
 * 2. FileProvider (needed for IN_APP and DIRECT install):
 *    &lt;provider
 *        android:name="androidx.core.content.FileProvider"
 *        android:authorities="${applicationId}.provider"
 *        android:exported="false"
 *        android:grantUriPermissions="true"&gt;
 *        &lt;meta-data
 *            android:name="android.support.FILE_PROVIDER_PATHS"
 *            android:resource="@xml/provider_paths" /&gt;
 *    &lt;/provider&gt;
 *
 * 3. res/xml/provider_paths.xml:
 *    &lt;paths xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 *        &lt;external-files-path name="external_files" path="."/&gt;
 *    &lt;/paths&gt;
 * </pre>
 */
public class OTAManager3 {

    // ============================================================================
    // CONFIGURATION DEFAULTS - used unless overridden by the fluent setters below.
    // Editing these still works exactly as before for a single-project template.
    // ============================================================================

    /** Release provider used to look up releases. */
    private static final Provider DEFAULT_PROVIDER = Provider.GITLAB;

    /** GitLab/GitHub project owner/username. */
    private static final String GITLAB_OWNER = "ailife8881";

    /** GitLab/GitHub project repository name. */
    private static final String GITLAB_REPO = "otamanager2";

    /** GitLab API base URL (change for self-hosted GitLab). */
    private static final String GITLAB_API_BASE = "https://gitlab.com/api/v4";

    /** GitHub API base URL (change for GitHub Enterprise). */
    private static final String GITHUB_API_BASE = "https://api.github.com";

    /** Personal Access Token (optional). Leave null for public repositories. */
    private static final String GITLAB_TOKEN = null;

    /** Default download mode. */
    private static final DownloadMode DOWNLOAD_MODE = DownloadMode.DOWNLOAD_MODE_IN_APP;

    /** Regex matched against asset name and URL to find the APK. */
    private static final String APK_FILE_PATTERN = ".*\\.apk$";

    /** Downloaded APK file name (IN_APP and DIRECT modes). */
    private static final String DOWNLOAD_FILE_NAME = "OTA.apk";

    /** FileProvider authority suffix (appended to the package name). */
    private static final String PROVIDER_PATH = ".provider";

    /** Enable debug logging. */
    private static final boolean DEBUG = true;

    /** Check for updates only on WiFi (overridable by the {@code force} parameter). */
    private static final boolean WIFI_ONLY = true;

    /**
     * When using the built-in UI, show a toast when already up to date / on a prerelease.
     * Kept {@code true} to preserve the original hands-off behavior.
     */
    private static final boolean SHOW_NO_UPDATE_TOAST = true;

    /**
     * When the built-in dialog is used, offer a "Skip this version" button
     * (in place of "Do not remind"). Off by default to preserve the original
     * three-button layout. The programmatic skip API is always available.
     */
    private static final boolean SHOW_SKIP_BUTTON = false;

    private static final String PREFS_NAME = "OTAManager3Prefs";
    private static final String PREF_UPDATE_ENABLED = "update_check_enabled";
    private static final String PREF_SKIPPED_VERSION = "skipped_version";

    // ============================================================================
    // END OF CONFIGURATION
    // ============================================================================

    private static final String TAG = "OTAManager3";
    private static final String MIME_TYPE = "application/vnd.android.package-archive";

    private final Context context;
    private final Handler mainHandler;

    // --- Runtime configuration (initialized from the defaults above) ---
    private Provider provider = DEFAULT_PROVIDER;
    private String owner = GITLAB_OWNER;
    private String repo = GITLAB_REPO;
    private String gitlabApiBase = GITLAB_API_BASE;
    private String githubApiBase = GITHUB_API_BASE;
    private String token = GITLAB_TOKEN;
    private DownloadMode downloadMode = DOWNLOAD_MODE;
    private String apkPattern = APK_FILE_PATTERN;
    private String downloadFileName = DOWNLOAD_FILE_NAME;
    private String providerAuthoritySuffix = PROVIDER_PATH;
    private boolean debug = DEBUG;
    private boolean wifiOnly = WIFI_ONLY;
    private boolean showNoUpdateToast = SHOW_NO_UPDATE_TOAST;
    private boolean showSkipButton = SHOW_SKIP_BUTTON;
    private Callback callback = null;

    /** Result of the most recent successful check (may be null). */
    private UpdateInfo lastUpdateInfo;

    // ============================================================================
    // PUBLIC TYPES
    // ============================================================================

    /** Where releases are fetched from. */
    public enum Provider {
        GITLAB,
        GITHUB
    }

    /**
     * How an update is downloaded/installed. The legacy names are preserved for
     * backward compatibility.
     */
    public enum DownloadMode {
        /** Open the download URL in the external browser. No extra permission needed. */
        DOWNLOAD_MODE_BROWSER,
        /** Download via the Android {@link DownloadManager}, then prompt install (legacy default). */
        DOWNLOAD_MODE_IN_APP,
        /** Stream the APK inside the app with progress callbacks, then prompt install. */
        DOWNLOAD_MODE_DIRECT,
        /** Show a chooser so the user picks one of the three modes above. */
        DOWNLOAD_MODE_ASK
    }

    /** Error categories reported to {@link Callback#onError}. */
    public enum OTAError {
        DISABLED,        // update checks turned off by the user
        NOT_ON_WIFI,     // wifi-only and not on wifi
        NETWORK,         // connection/timeout/HTTP error
        SERVER,          // non-200 response
        PARSE,           // could not parse the release response
        NO_APK,          // release found but no matching APK asset
        NO_RELEASE       // provider returned no releases
    }

    /** Metadata for a single released version. */
    public static class VersionInfo {
        public String version;        // normalized, e.g. "4.1.0"
        public String tagName;        // raw tag, e.g. "v4.1.0"
        public String changelog;      // markdown-cleaned description
        public String rawChangelog;   // original markdown description
        public String downloadUrl;    // APK url (may be null if none matched)
        public String releasedAt;     // ISO timestamp if provided
    }

    /** Aggregate result of a check. */
    public static class UpdateInfo {
        public String currentVersion;              // installed versionName
        public String latestVersion;               // newest available version
        public String latestTag;                   // newest available raw tag
        public String changelog;                   // latest version changelog (cleaned)
        public String rawChangelog;                // latest version changelog (markdown)
        public String changelogSinceCurrent;       // combined changelog of every version newer than current
        public String downloadUrl;                 // APK url for the latest version
        public boolean updateAvailable;            // currentVersion < latestVersion
        public boolean prerelease;                 // currentVersion > latestVersion
        public List<VersionInfo> allVersions = new ArrayList<>(); // every release, newest first
    }

    /**
     * Receives every stage of the update lifecycle. When a listener is set, the
     * manager does NOT show any of its own UI — you are fully in control. Call
     * {@link #download(UpdateInfo, DownloadMode)} (or the built-in
     * {@link #showDefaultUpdateDialog(UpdateInfo)}) from your handlers.
     *
     * <p>All methods are delivered on the main thread.</p>
     */
    public interface Callback {
        /** A check has started (network request about to run). */
        void onCheckStarted();

        /** An update is available. Show your UI, then call {@link #download}. */
        void onUpdateAvailable(UpdateInfo info);

        /** Already on the latest (or a newer/prerelease) version. */
        void onUpToDate(UpdateInfo info);

        /** The check or download failed. */
        void onError(OTAError error, String message, Exception exception);

        /** A download has started in the given mode. */
        void onDownloadStarted(DownloadMode mode);

        /** DIRECT-mode progress. {@code percent} is -1 when the total size is unknown. */
        void onDownloadProgress(int percent, long bytesDownloaded, long totalBytes);

        /** The APK finished downloading (IN_APP/DIRECT). Install is triggered right after. */
        void onDownloadComplete(File apkFile);

        /** A download failed. */
        void onDownloadFailed(String message, Exception exception);

        /** The user declined via the built-in dialog. {@code dontAskAgain} = "Do not remind". */
        void onUpdateDeclined(boolean dontAskAgain);

        /** The user chose to skip this version (built-in dialog, or your own call to {@link #skipVersion}). */
        void onVersionSkipped(String version);
    }

    /** Adapter with empty implementations - override only what you need. */
    public static abstract class SimpleCallback implements Callback {
        @Override public void onCheckStarted() { }
        @Override public void onUpdateAvailable(UpdateInfo info) { }
        @Override public void onUpToDate(UpdateInfo info) { }
        @Override public void onError(OTAError error, String message, Exception exception) { }
        @Override public void onDownloadStarted(DownloadMode mode) { }
        @Override public void onDownloadProgress(int percent, long bytesDownloaded, long totalBytes) { }
        @Override public void onDownloadComplete(File apkFile) { }
        @Override public void onDownloadFailed(String message, Exception exception) { }
        @Override public void onUpdateDeclined(boolean dontAskAgain) { }
        @Override public void onVersionSkipped(String version) { }
    }

    // ============================================================================
    // CONSTRUCTION & CONFIGURATION
    // ============================================================================

    /**
     * @param context Application or Activity context. Use an Activity context if you
     *                rely on the built-in dialogs.
     */
    public OTAManager3(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public OTAManager3 setProvider(Provider provider) { this.provider = provider; return this; }
    public OTAManager3 setRepo(String owner, String repo) { this.owner = owner; this.repo = repo; return this; }
    public OTAManager3 setOwner(String owner) { this.owner = owner; return this; }
    public OTAManager3 setRepoName(String repo) { this.repo = repo; return this; }
    public OTAManager3 setToken(String token) { this.token = token; return this; }
    public OTAManager3 setGitlabApiBase(String base) { this.gitlabApiBase = base; return this; }
    public OTAManager3 setGithubApiBase(String base) { this.githubApiBase = base; return this; }
    public OTAManager3 setDownloadMode(DownloadMode mode) { this.downloadMode = mode; return this; }
    public OTAManager3 setApkPattern(String pattern) { this.apkPattern = pattern; return this; }
    public OTAManager3 setDownloadFileName(String name) { this.downloadFileName = name; return this; }
    public OTAManager3 setProviderAuthoritySuffix(String suffix) { this.providerAuthoritySuffix = suffix; return this; }
    public OTAManager3 setDebug(boolean debug) { this.debug = debug; return this; }
    public OTAManager3 setWifiOnly(boolean wifiOnly) { this.wifiOnly = wifiOnly; return this; }
    public OTAManager3 setShowNoUpdateToast(boolean show) { this.showNoUpdateToast = show; return this; }
    public OTAManager3 setShowSkipButton(boolean show) { this.showSkipButton = show; return this; }

    /** Set the listener that takes over all UI. Pass {@code null} to restore built-in UI. */
    public OTAManager3 setListener(Callback callback) { this.callback = callback; return this; }

    /** @return the result of the most recent successful check, or {@code null}. */
    public UpdateInfo getLastUpdateInfo() { return lastUpdateInfo; }

    // ============================================================================
    // ENABLE / DISABLE (backward compatible)
    // ============================================================================

    public boolean isUpdateCheckEnabled() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_UPDATE_ENABLED, true);
    }

    public void setUpdateCheckEnabled(boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_UPDATE_ENABLED, enabled)
                .apply();
        logDebug("Update check " + (enabled ? "enabled" : "disabled"));
    }

    // ============================================================================
    // SKIP THIS VERSION
    // ============================================================================

    /**
     * Skip a specific version so the built-in check will not prompt for it again.
     * Newer versions still prompt; a forced {@link #checkUpdate(boolean) checkUpdate(true)}
     * ignores the skip. Call with a version like {@code "4.1.0"}.
     */
    public void skipVersion(String version) {
        if (version == null || version.isEmpty()) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_SKIPPED_VERSION, version)
                .apply();
        logDebug("Skipping version: " + version);
        if (callback != null) {
            final String v = version;
            mainHandler.post(new Runnable() {
                @Override public void run() { callback.onVersionSkipped(v); }
            });
        }
    }

    /** @return the skipped version string, or {@code null} if none. */
    public String getSkippedVersion() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_SKIPPED_VERSION, null);
    }

    /**
     * @return true if {@code version} should be suppressed, i.e. it is not newer than
     *         the skipped version.
     */
    public boolean isVersionSkipped(String version) {
        String skipped = getSkippedVersion();
        if (skipped == null || skipped.isEmpty() || version == null) return false;
        return compareVersions(version, skipped) <= 0;
    }

    /** Clear any skipped version so all updates prompt again. */
    public void clearSkippedVersion() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_SKIPPED_VERSION)
                .apply();
        logDebug("Cleared skipped version");
    }

    // ============================================================================
    // CHECK FOR UPDATES
    // ============================================================================

    /** Convenience: check honoring WiFi-only and the enabled flag. */
    public void checkUpdate() {
        checkUpdate(false);
    }

    /**
     * Check for updates.
     * @param force Force the check even on mobile data / when disabled by the user.
     */
    public void checkUpdate(final boolean force) {
        // Respect the user's disable preference.
        if (!force && !isUpdateCheckEnabled()) {
            logDebug("Update check disabled by user");
            dispatchError(OTAError.DISABLED, context.getString(R.string.ota3_err_disabled), null);
            return;
        }

        // WiFi-only gate.
        if (!force && wifiOnly && !isOnWifi()) {
            logDebug("Not on WiFi, skipping update check");
            dispatchError(OTAError.NOT_ON_WIFI, context.getString(R.string.ota3_err_not_on_wifi), null);
            return;
        }

        final String apiUrl = buildReleasesUrl();
        logDebug("Checking for updates at: " + apiUrl);
        dispatchCheckStarted();

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(apiUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    applyAuth(connection);
                    if (provider == Provider.GITHUB) {
                        connection.setRequestProperty("Accept", "application/vnd.github+json");
                    }

                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        logError("API request failed with code: " + responseCode);
                        dispatchError(OTAError.SERVER, context.getString(R.string.ota3_err_server_code, responseCode), null);
                        return;
                    }

                    String response = readStream(connection.getInputStream());
                    List<VersionInfo> versions = parseReleases(response);
                    if (versions.isEmpty()) {
                        logError("No releases found");
                        dispatchError(OTAError.NO_RELEASE, context.getString(R.string.ota3_err_no_release), null);
                        return;
                    }
                    evaluate(versions, force);

                } catch (JSONException e) {
                    logError("JSON parsing error: " + e.getMessage());
                    dispatchError(OTAError.PARSE, context.getString(R.string.ota3_err_parse), e);
                } catch (Exception e) {
                    logError("API request failed: " + e.getMessage());
                    dispatchError(OTAError.NETWORK, context.getString(R.string.ota3_err_network, e.getMessage()), e);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    private String buildReleasesUrl() {
        if (provider == Provider.GITHUB) {
            // Newest first, up to 100 releases.
            return githubApiBase + "/repos/" + owner + "/" + repo + "/releases?per_page=100";
        }
        // GitLab: URL-encode the "owner/repo" project path.
        String encodedPath = (owner + "/" + repo).replace("/", "%2F");
        return gitlabApiBase + "/projects/" + encodedPath + "/releases?per_page=100";
    }

    private void applyAuth(HttpURLConnection connection) {
        if (token == null || token.isEmpty()) {
            return;
        }
        if (provider == Provider.GITHUB) {
            connection.setRequestProperty("Authorization", "token " + token);
        } else {
            connection.setRequestProperty("PRIVATE-TOKEN", token);
        }
    }

    // ============================================================================
    // PARSING
    // ============================================================================

    /** Parse the provider's releases-list JSON into normalized {@link VersionInfo}s. */
    private List<VersionInfo> parseReleases(String json) throws JSONException {
        List<VersionInfo> out = new ArrayList<>();
        JSONArray releases = new JSONArray(json);
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            VersionInfo vi = (provider == Provider.GITHUB)
                    ? parseGithubRelease(release)
                    : parseGitlabRelease(release);
            if (vi != null) {
                out.add(vi);
            }
        }
        // Sort newest-first by semantic version so index 0 is the latest.
        Collections.sort(out, new Comparator<VersionInfo>() {
            @Override
            public int compare(VersionInfo a, VersionInfo b) {
                return compareVersions(b.version, a.version);
            }
        });
        return out;
    }

    private VersionInfo parseGitlabRelease(JSONObject release) {
        VersionInfo vi = new VersionInfo();
        vi.tagName = release.optString("tag_name", "");
        vi.version = normalizeVersion(vi.tagName);
        vi.releasedAt = release.optString("released_at", "");
        vi.rawChangelog = release.optString("description", "");
        vi.changelog = cleanMarkdown(vi.rawChangelog);

        JSONObject assets = release.optJSONObject("assets");
        if (assets != null) {
            JSONArray links = assets.optJSONArray("links");
            if (links != null) {
                for (int i = 0; i < links.length(); i++) {
                    JSONObject link = links.optJSONObject(i);
                    if (link == null) continue;
                    String name = link.optString("name", "");
                    String url = link.optString("url", "");
                    if (name.matches(apkPattern) || url.matches(apkPattern)) {
                        vi.downloadUrl = url;
                        break;
                    }
                }
            }
            if (vi.downloadUrl == null) {
                JSONArray sources = assets.optJSONArray("sources");
                if (sources != null) {
                    for (int i = 0; i < sources.length(); i++) {
                        JSONObject source = sources.optJSONObject(i);
                        if (source == null) continue;
                        if ("apk".equals(source.optString("format", ""))) {
                            vi.downloadUrl = source.optString("url", null);
                            break;
                        }
                    }
                }
            }
        }
        return vi;
    }

    private VersionInfo parseGithubRelease(JSONObject release) {
        // Skip drafts; keep prereleases (they are still installable releases).
        if (release.optBoolean("draft", false)) {
            return null;
        }
        VersionInfo vi = new VersionInfo();
        vi.tagName = release.optString("tag_name", "");
        vi.version = normalizeVersion(vi.tagName);
        vi.releasedAt = release.optString("published_at", "");
        vi.rawChangelog = release.optString("body", "");
        vi.changelog = cleanMarkdown(vi.rawChangelog);

        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (name.matches(apkPattern) || url.matches(apkPattern)) {
                    vi.downloadUrl = url;
                    break;
                }
            }
        }
        return vi;
    }

    /** Build the {@link UpdateInfo}, compare against the installed version, and dispatch. */
    private void evaluate(List<VersionInfo> versions, boolean force) {
        String currentVersion;
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            currentVersion = pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            logError("Failed to get package info: " + e.getMessage());
            dispatchError(OTAError.PARSE, context.getString(R.string.ota3_err_read_current), e);
            return;
        }

        // Latest = the release with an APK and the highest version. Fall back to the
        // newest release even if it has no APK so we can still report a changelog.
        VersionInfo latest = null;
        for (VersionInfo v : versions) {
            if (v.downloadUrl != null) { latest = v; break; }
        }
        if (latest == null) {
            latest = versions.get(0);
        }

        UpdateInfo info = new UpdateInfo();
        info.currentVersion = currentVersion;
        info.latestVersion = latest.version;
        info.latestTag = latest.tagName;
        info.changelog = latest.changelog;
        info.rawChangelog = latest.rawChangelog;
        info.downloadUrl = latest.downloadUrl;
        info.allVersions = versions;

        int cmp = compareVersions(currentVersion, latest.version);
        info.updateAvailable = cmp < 0;
        info.prerelease = cmp > 0;
        info.changelogSinceCurrent = buildChangelogSince(currentVersion, versions);

        this.lastUpdateInfo = info;

        logDebug("Current: " + currentVersion + " | Latest: " + latest.version
                + " | updateAvailable=" + info.updateAvailable);

        if (info.updateAvailable) {
            if (!force && isVersionSkipped(info.latestVersion)) {
                logDebug("Version " + info.latestVersion + " was skipped by the user");
                return; // silent: lastUpdateInfo is still set for host queries
            }
            if (info.downloadUrl == null) {
                logError("Update available but no APK asset matched");
                dispatchError(OTAError.NO_APK, context.getString(R.string.ota3_err_no_apk), null);
                return;
            }
            dispatchUpdateAvailable(info);
        } else {
            dispatchUpToDate(info);
        }
    }

    /** Concatenate the changelog of every release strictly newer than {@code currentVersion}. */
    private String buildChangelogSince(String currentVersion, List<VersionInfo> versions) {
        StringBuilder sb = new StringBuilder();
        for (VersionInfo v : versions) { // already newest-first
            if (compareVersions(currentVersion, v.version) < 0) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("v").append(v.version).append("\n");
                sb.append(v.changelog == null || v.changelog.isEmpty()
                        ? context.getString(R.string.ota3_changelog_none) : v.changelog);
            }
        }
        return sb.length() == 0 ? "" : sb.toString().trim();
    }

    // ============================================================================
    // DISPATCH (listener or built-in UI)
    // ============================================================================

    private void dispatchCheckStarted() {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onCheckStarted(); }
        });
    }

    private void dispatchUpdateAvailable(final UpdateInfo info) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) {
                    callback.onUpdateAvailable(info);
                } else {
                    showDefaultUpdateDialog(info);
                }
            }
        });
    }

    private void dispatchUpToDate(final UpdateInfo info) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) {
                    callback.onUpToDate(info);
                } else if (showNoUpdateToast) {
                    toast(context.getString(info.prerelease ? R.string.ota3_prerelease : R.string.ota3_up_to_date));
                }
            }
        });
    }

    private void dispatchError(final OTAError error, final String message, final Exception e) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) {
                    callback.onError(error, message, e);
                } else if (shouldToastError(error)) {
                    toast(message);
                }
            }
        });
    }

    /** The built-in UI stays quiet about "disabled"/"not on wifi" (silent hands-off skips). */
    private boolean shouldToastError(OTAError error) {
        return error != OTAError.DISABLED && error != OTAError.NOT_ON_WIFI;
    }

    // ============================================================================
    // BUILT-IN DIALOGS (used when no listener is set; also callable by the host)
    // ============================================================================

    /** Show the built-in update dialog. Safe to call from a listener to reuse default UI. */
    public void showDefaultUpdateDialog(final UpdateInfo info) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String body = info.changelogSinceCurrent != null && !info.changelogSinceCurrent.isEmpty()
                        ? info.changelogSinceCurrent
                        : (info.changelog == null || info.changelog.isEmpty()
                            ? context.getString(R.string.ota3_changelog_none) : info.changelog);

                AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.ota3_dialog_title, info.latestVersion))
                    .setMessage(body)
                    .setCancelable(true)
                    .setPositiveButton(context.getString(R.string.ota3_btn_update), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            download(info, downloadMode);
                        }
                    })
                    .setNeutralButton(context.getString(R.string.ota3_btn_later), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (callback != null) callback.onUpdateDeclined(false);
                            d.dismiss();
                        }
                    });

                if (showSkipButton) {
                    // Skip this version instead of disabling all future checks.
                    builder.setNegativeButton(context.getString(R.string.ota3_btn_skip), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            skipVersion(info.latestVersion);
                            if (callback == null) {
                                toast(context.getString(R.string.ota3_toast_skipped, info.latestVersion));
                            }
                            d.dismiss();
                        }
                    });
                } else {
                    builder.setNegativeButton(context.getString(R.string.ota3_btn_do_not_remind), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            setUpdateCheckEnabled(false);
                            if (callback != null) {
                                callback.onUpdateDeclined(true);
                            } else {
                                toast(context.getString(R.string.ota3_toast_disabled));
                            }
                            d.dismiss();
                        }
                    });
                }

                builder.show();
            }
        });
    }

    /** Show a chooser letting the user pick browser / DownloadManager / direct download. */
    public void showDownloadModeChooser(final UpdateInfo info) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                final DownloadMode[] modes = {
                        DownloadMode.DOWNLOAD_MODE_DIRECT,
                        DownloadMode.DOWNLOAD_MODE_IN_APP,
                        DownloadMode.DOWNLOAD_MODE_BROWSER
                };
                final String[] labels = {
                        context.getString(R.string.ota3_chooser_item_in_app),
                        context.getString(R.string.ota3_chooser_item_manager),
                        context.getString(R.string.ota3_chooser_item_browser)
                };
                new AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.ota3_chooser_title))
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            download(info, modes[which]);
                        }
                    })
                    .show();
            }
        });
    }

    // ============================================================================
    // DOWNLOAD (public entry point - host may call with any mode)
    // ============================================================================

    /** Start a download/install using the given mode. */
    public void download(UpdateInfo info, DownloadMode mode) {
        if (info == null || info.downloadUrl == null) {
            dispatchDownloadFailed(context.getString(R.string.ota3_err_no_url), null);
            return;
        }
        switch (mode) {
            case DOWNLOAD_MODE_BROWSER:
                openInBrowser(info.downloadUrl);
                break;
            case DOWNLOAD_MODE_DIRECT:
                downloadDirect(info);
                break;
            case DOWNLOAD_MODE_ASK:
                showDownloadModeChooser(info);
                break;
            case DOWNLOAD_MODE_IN_APP:
            default:
                downloadWithManager(info);
                break;
        }
    }

    /** Download the currently-known latest update using the configured mode. */
    public void downloadLatest() {
        download(lastUpdateInfo, downloadMode);
    }

    // ============================================================================
    // MODE 1: DownloadManager
    // ============================================================================

    private void downloadWithManager(final UpdateInfo info) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dispatchDownloadFailed(context.getString(R.string.ota3_err_storage_unavailable), null);
            return;
        }
        try {
            String destination = dir.toString() + "/" + downloadFileName;
            File file = new File(destination);
            if (file.exists()) {
                file.delete();
            }

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.downloadUrl));
            request.setMimeType(MIME_TYPE);
            request.setTitle(context.getString(R.string.ota3_notif_title));
            request.setDescription(context.getString(R.string.ota3_notif_desc, info.latestVersion));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(context, null, downloadFileName);

            setupDownloadCompleteReceiver(destination);

            downloadManager.enqueue(request);
            dispatchDownloadStarted(DownloadMode.DOWNLOAD_MODE_IN_APP);
            if (callback == null) {
                toast(context.getString(R.string.ota3_toast_downloading));
            }
            logDebug("DownloadManager started: " + info.downloadUrl);
        } catch (Throwable t) {
            // DownloadManager can reject the destination path (e.g. SecurityException
            // "Unsupported path" when running in a cloned/secondary user profile),
            // or fail for other reasons. Don't crash — report failure and offer browser fallback.
            logError("In-app download failed: " + t);
            dispatchDownloadFailed(context.getString(R.string.ota3_err_inapp_failed), t instanceof Exception ? (Exception) t : null);
            toast(context.getString(R.string.ota3_toast_inapp_failed));
            try {
                openInBrowser(info.downloadUrl);
            } catch (Throwable t2) {
                logError("Browser fallback also failed: " + t2);
            }
        }
    }

    private void setupDownloadCompleteReceiver(final String destination) {
        BroadcastReceiver onComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                logDebug("Download complete, installing APK");
                File apkFile = new File(destination);
                if (!apkFile.exists()) {
                    dispatchDownloadFailed(context.getString(R.string.ota3_err_file_missing), null);
                    try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
                    return;
                }
                dispatchDownloadComplete(apkFile);
                installApk(ctx, apkFile);
                try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(onComplete, filter);
        }
    }

    // ============================================================================
    // MODE 2: Direct in-app streaming download (with progress)
    // ============================================================================

    private void downloadDirect(final UpdateInfo info) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dispatchDownloadFailed(context.getString(R.string.ota3_err_storage_unavailable), null);
            return;
        }
        final File apkFile = new File(dir, downloadFileName);
        if (apkFile.exists()) {
            apkFile.delete();
        }

        dispatchDownloadStarted(DownloadMode.DOWNLOAD_MODE_DIRECT);
        if (callback == null) {
            toast(context.getString(R.string.ota3_toast_downloading));
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                InputStream input = null;
                OutputStream output = null;
                try {
                    URL url = new URL(info.downloadUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(20000);
                    connection.setInstanceFollowRedirects(true);
                    applyAuth(connection);
                    connection.connect();

                    int code = connection.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        dispatchDownloadFailed(context.getString(R.string.ota3_err_download_server_code, code), null);
                        return;
                    }

                    final long total = connection.getContentLength(); // -1 if unknown
                    input = connection.getInputStream();
                    output = new FileOutputStream(apkFile);

                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    int read;
                    int lastPercent = -1;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        downloaded += read;
                        int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            dispatchDownloadProgress(percent, downloaded, total);
                        }
                    }
                    output.flush();

                    logDebug("Direct download complete: " + apkFile.getAbsolutePath());
                    dispatchDownloadComplete(apkFile);
                    mainHandler.post(new Runnable() {
                        @Override public void run() { installApk(context, apkFile); }
                    });

                } catch (Exception e) {
                    logError("Direct download failed: " + e.getMessage());
                    dispatchDownloadFailed(context.getString(R.string.ota3_err_download, e.getMessage()), e);
                } finally {
                    closeQuietly(output);
                    closeQuietly(input);
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    // ============================================================================
    // MODE 3: Browser
    // ============================================================================

    private void openInBrowser(String downloadUrl) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browserIntent);
            dispatchDownloadStarted(DownloadMode.DOWNLOAD_MODE_BROWSER);
            if (callback == null) {
                toast(context.getString(R.string.ota3_toast_browser));
            }
            logDebug("Opened in browser: " + downloadUrl);
        } catch (Exception e) {
            logError("Failed to open browser: " + e.getMessage());
            dispatchDownloadFailed(context.getString(R.string.ota3_err_browser), e);
        }
    }

    // ============================================================================
    // INSTALL
    // ============================================================================

    private void installApk(Context ctx, File apkFile) {
        try {
            if (!apkFile.exists()) {
                dispatchDownloadFailed(context.getString(R.string.ota3_err_file_missing), null);
                return;
            }
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        ctx, ctx.getPackageName() + providerAuthoritySuffix, apkFile);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, MIME_TYPE);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(install);
        } catch (Exception e) {
            logError("Failed to install APK: " + e.getMessage());
            dispatchDownloadFailed(context.getString(R.string.ota3_err_install, e.getMessage()), e);
        }
    }

    // ============================================================================
    // DOWNLOAD DISPATCH HELPERS
    // ============================================================================

    private void dispatchDownloadStarted(final DownloadMode mode) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onDownloadStarted(mode); }
        });
    }

    private void dispatchDownloadProgress(final int percent, final long done, final long total) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onDownloadProgress(percent, done, total); }
        });
    }

    private void dispatchDownloadComplete(final File file) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onDownloadComplete(file); }
        });
    }

    private void dispatchDownloadFailed(final String message, final Exception e) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) {
                    callback.onDownloadFailed(message, e);
                } else {
                    toast(message);
                }
            }
        });
    }

    // ============================================================================
    // HELPERS
    // ============================================================================

    private boolean isOnWifi() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private String readStream(InputStream inputStream) throws java.io.IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    /** Strip a leading 'v'/'V' from a tag, e.g. "v4.1.0" -> "4.1.0". */
    private String normalizeVersion(String tag) {
        if (tag == null) return "";
        String v = tag.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        return v;
    }

    /**
     * Compare two semantic version strings.
     * @return -1 if v1 &lt; v2, 0 if equal, 1 if v1 &gt; v2.
     */
    private int compareVersions(String version1, String version2) {
        version1 = version1 == null ? "" : version1.replaceAll("^[^0-9]+", "");
        version2 = version2 == null ? "" : version2.replaceAll("^[^0-9]+", "");

        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");
        int maxLength = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLength; i++) {
            int a = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int b = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (a < b) return -1;
            if (a > b) return 1;
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        StringBuilder digits = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        try {
            return digits.length() > 0 ? Integer.parseInt(digits.toString()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Remove common markdown formatting for plain-text display. */
    private String cleanMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1"); // links
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");         // **bold**
        text = text.replaceAll("__([^_]+)__", "$1");                 // __bold__
        text = text.replaceAll("\\*([^*]+)\\*", "$1");               // *italic*
        text = text.replaceAll("_([^_]+)_", "$1");                   // _italic_
        text = text.replaceAll("`([^`]+)`", "$1");                   // `code`
        text = text.replaceAll("^#{1,6}\\s+", "");                   // headers
        text = text.replaceAll("\\n#{1,6}\\s+", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private void toast(final String message) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void logDebug(String message) {
        if (debug) {
            Log.d(TAG, message);
        }
    }

    private void logError(String message) {
        Log.e(TAG, message);
    }
}
