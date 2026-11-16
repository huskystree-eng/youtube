package com.litetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lite TV v1.1: un WebView a pantalla completa que abre YouTube.
 *
 * - Modo TV: youtube.com/tv (interfaz para control remoto).
 * - Modo Móvil: m.youtube.com (más liviano, mejor con mouse o touch).
 * - Limita la calidad máxima (144p a 720p).
 * - Muestra un panel de diagnóstico en pantalla (versión de WebView, red, errores).
 * - Mantené apretado ATRÁS (o tocá MENÚ) para abrir el menú de la app.
 */
public class MainActivity extends Activity {

    private static final String VERSION = "1.1";
    private static final String PREFS = "litetv";
    private static final String URL_TV = "https://www.youtube.com/tv";
    private static final String URL_MOBILE = "https://m.youtube.com/";
    private static final String URL_TEST = "https://example.com/";

    // User-Agent que hace que YouTube entregue la interfaz de TV.
    private static final String UA_TV =
            "Mozilla/5.0 (PlayStation; PlayStation 4/11.00) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/15.0 Safari/605.1.15";

    private static final String[] Q_LABELS = {"144p", "240p", "360p", "480p", "720p"};
    private static final String[] Q_KEYS = {"tiny", "small", "medium", "large", "hd720"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryLoad = new Runnable() {
        @Override
        public void run() {
            lastError = "";
            loadHome();
        }
    };
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            if (!pinStatus && lastError.length() == 0) {
                status.setVisibility(View.GONE);
            }
        }
    };

    private FrameLayout root;
    private WebView web;
    private TextView status;
    private WebChromeClient chrome;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private SharedPreferences prefs;
    private String defaultUa;
    private String chromeVersion = "desconocido";
    private String currentUrl = "";
    private String lastError = "";
    private int progress = 0;
    private boolean tvMode;
    private boolean softRender;
    private boolean pinStatus;
    private int qIndex;
    private long lastBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        tvMode = prefs.getBoolean("tv", false); // arranca en modo Móvil (más liviano)
        qIndex = prefs.getInt("q", 2); // 360p por defecto
        softRender = prefs.getBoolean("sw", false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xCC000000);
        status.setTextSize(16);
        status.setPadding(24, 16, 24, 16);
        status.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(status, lp);

        setupWebView();
        applyMode();
        updateStatus();

        Toast.makeText(this, "Mantené apretado ATRÁS para abrir el menú", Toast.LENGTH_LONG).show();
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        defaultUa = s.getUserAgentString();
        Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(defaultUa);
        if (m.find()) chromeVersion = m.group(1);

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        if (softRender) web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.requestFocus();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url;
                status.setVisibility(View.VISIBLE);
                updateStatus();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                injectQuality();
                updateStatus();
                handler.removeCallbacks(hideStatus);
                handler.postDelayed(hideStatus, 6000);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Solo http/https; ignora intents y esquemas raros.
                return url == null || !url.startsWith("http");
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                lastError = errorCode + " " + description;
                status.setVisibility(View.VISIBLE);
                updateStatus();
                handler.removeCallbacks(retryLoad);
                handler.postDelayed(retryLoad, 8000);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    lastError = "HTTP " + errorResponse.getStatusCode();
                    status.setVisibility(View.VISIBLE);
                    updateStatus();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
                h.cancel();
                lastError = "SSL (" + error.getPrimaryError() + "). Revisá la fecha y hora del box.";
                status.setVisibility(View.VISIBLE);
                updateStatus();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // El box se quedó sin memoria y mató el render: reiniciamos la pantalla.
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        recreate();
                    }
                });
                return true;
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress = newProgress;
                updateStatus();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = cb;
                web.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCb != null) {
                    customCb.onCustomViewHidden();
                    customCb = null;
                }
                web.setVisibility(View.VISIBLE);
                web.requestFocus();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        };
        web.setWebChromeClient(chrome);
    }

    private void applyMode() {
        WebSettings s = web.getSettings();
        if (tvMode) {
            s.setUserAgentString(UA_TV);
        } else {
            s.setUserAgentString(defaultUa.replace("; wv", ""));
        }
        lastError = "";
        loadHome();
    }

    private void loadHome() {
        currentUrl = tvMode ? URL_TV : URL_MOBILE;
        web.loadUrl(currentUrl);
        updateStatus();
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void updateStatus() {
        status.setText("Lite TV v" + VERSION
                + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nWebView: Chrome " + chromeVersion
                + "\nRed: " + (isOnline() ? "conectada" : "SIN CONEXION")
                + " | Modo: " + (tvMode ? "TV" : "Móvil")
                + " | Render: " + (softRender ? "software" : "hardware")
                + "\nCarga: " + progress + "%"
                + "\nURL: " + currentUrl
                + (lastError.length() > 0 ? "\nERROR: " + lastError : ""));
    }

    /** Fuerza la calidad máxima elegida cada 2 segundos mientras el reproductor esté activo. */
    private void injectQuality() {
        String js = "window.__ytq='" + Q_KEYS[qIndex] + "';"
                + "if(!window.__ytlite){window.__ytlite=1;setInterval(function(){try{"
                + "var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');"
                + "if(p&&p.setPlaybackQualityRange){"
                + "if(!p.getPlaybackQuality||p.getPlaybackQuality()!==window.__ytq){"
                + "p.setPlaybackQualityRange(window.__ytq,window.__ytq);"
                + "if(p.setPlaybackQuality)p.setPlaybackQuality(window.__ytq);}}"
                + "}catch(e){}},2000);}";
        web.evaluateJavascript(js, null);
    }

    // ---------------------------------------------------------------- menú

    private void showMenu() {
        final String[] items = {
                "Modo: " + (tvMode ? "TV (control remoto)" : "Móvil (mouse/touch)") + "  -> cambiar",
                "Calidad máxima: " + Q_LABELS[qIndex] + "  -> cambiar",
                "Render: " + (softRender ? "software" : "hardware") + "  -> cambiar (si ves todo negro)",
                "Probar con página simple (example.com)",
                "Mostrar / ocultar diagnóstico",
                "Recargar",
                "Ir al inicio",
                "Limpiar caché",
                "Salir"
        };
        new AlertDialog.Builder(this)
                .setTitle("Lite TV v" + VERSION)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                tvMode = !tvMode;
                                prefs.edit().putBoolean("tv", tvMode).apply();
                                applyMode();
                                break;
                            case 1:
                                showQualityDialog();
                                break;
                            case 2:
                                softRender = !softRender;
                                prefs.edit().putBoolean("sw", softRender).apply();
                                web.setLayerType(softRender ? View.LAYER_TYPE_SOFTWARE
                                        : View.LAYER_TYPE_HARDWARE, null);
                                lastError = "";
                                loadHome();
                                break;
                            case 3:
                                lastError = "";
                                currentUrl = URL_TEST;
                                web.loadUrl(URL_TEST);
                                break;
                            case 4:
                                pinStatus = !pinStatus;
                                status.setVisibility(pinStatus || status.getVisibility() != View.VISIBLE
                                        ? View.VISIBLE : View.GONE);
                                updateStatus();
                                break;
                            case 5:
                                lastError = "";
                                web.reload();
                                break;
                            case 6:
                                lastError = "";
                                loadHome();
                                break;
                            case 7:
                                web.clearCache(true);
                                Toast.makeText(MainActivity.this, "Caché limpia", Toast.LENGTH_SHORT).show();
                                break;
                            default:
                                finish();
                        }
                    }
                })
                .show();
    }

    private void showQualityDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Calidad máxima")
                .setSingleChoiceItems(Q_LABELS, qIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        qIndex = which;
                        prefs.edit().putInt("q", qIndex).apply();
                        injectQuality();
                        d.dismiss();
                        Toast.makeText(MainActivity.this, "Calidad: " + Q_LABELS[qIndex],
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- teclas

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_GUIDE) {
            showMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showMenu();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking() && !event.isCanceled()) {
            handleBack();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void handleBack() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        if (tvMode) {
            // YouTube TV entiende Escape como "atrás".
            long t = SystemClock.uptimeMillis();
            web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
            web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBack < 2000) {
            finish();
        } else {
            lastBack = now;
            Toast.makeText(this, "Apretá ATRÁS otra vez para salir", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
