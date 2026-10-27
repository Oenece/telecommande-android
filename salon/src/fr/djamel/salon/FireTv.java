package fr.djamel.salon;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Service télécommande d'Amazon sur la Fire TV (celui de l'appli Fire TV officielle).
 * - DIAL (HTTP 8009) : POST /apps/FireTVRemote démarre le service, qui ouvre le port HTTPS 8080.
 * - HTTPS 8080, en-têtes x-api-key + x-client-token (jeton obtenu par pin/display puis pin/verify).
 * Le service s'arrête quand il reste inactif : sur échec, on le relance et on réessaie une fois.
 * Toutes les requêtes passent par un seul fil, donc les touches arrivent dans l'ordre.
 */
final class FireTv {
    static final String TAG = "Salon";
    private static final String API_KEY = "0987654321";

    interface Etat {
        void connecte(boolean ok);
    }

    interface Resultat<T> {
        void recu(T valeur);
    }

    static final class Appli {
        String paquet, nom, icone;
    }

    String ip;
    String jeton;
    Etat ecouteur;
    private final ExecutorService fil = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SSLSocketFactory ssl;
    private final HostnameVerifier toutHote = new HostnameVerifier() {
        @Override public boolean verify(String h, javax.net.ssl.SSLSession s) { return h.equals(ip); }
    };

    FireTv(String ip, String jeton) {
        this.ip = ip;
        this.jeton = jeton;
        ssl = sslSansVerification();
    }

    /** La Fire TV présente un certificat auto-signé : on l'accepte, mais seulement pour son adresse. */
    private static SSLSocketFactory sslSansVerification() {
        try {
            TrustManager[] confiance = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, confiance, null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- Commandes ----------

    /** dpad_up, dpad_down, dpad_left, dpad_right, select, home, back, menu, sleep */
    void touche(String action) {
        poster("/v1/FireTV?action=" + action, null);
    }

    void lecturePause() {
        poster("/v1/media?action=play", null);
    }

    void avance(boolean avant) {
        poster("/v1/media?action=scan",
                "{\"direction\":\"" + (avant ? "forward" : "back") + "\",\"keyAction\":{\"keyActionType\":\"keyDown\"}}");
    }

    void lancer(String paquet) {
        poster("/v1/FireTV/app/" + paquet, null);
    }

    void texte(String s) {
        try {
            poster("/v1/FireTV/keyboard", new JSONObject().put("text", s).toString());
        } catch (Exception ignore) {
        }
    }

    void applis(final Resultat<List<Appli>> rappel) {
        fil.execute(new Runnable() {
            @Override public void run() {
                List<Appli> liste = new ArrayList<>();
                try {
                    String json = requete("GET", "/v1/FireTV/apps", null, true);
                    JSONArray a = new JSONArray(json);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        if (!o.optBoolean("isInstalled")) continue;
                        Appli ap = new Appli();
                        ap.paquet = o.getString("appId");
                        ap.nom = o.optString("name");
                        ap.icone = o.optString("iconArtSmallUri");
                        liste.add(ap);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Liste des applis impossible : " + e);
                }
                final List<Appli> fin = liste;
                ui.post(new Runnable() { @Override public void run() { rappel.recu(fin); } });
            }
        });
    }

    /** Appairage, étape 1 : affiche un code à 4 chiffres sur la TV. */
    void afficherCode(final Resultat<Boolean> rappel) {
        fil.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    demarrerService();
                    requete("POST", "/v1/FireTV/pin/display", "{\"friendlyName\":\"Mi Max 3\"}", false);
                    ok = true;
                } catch (Exception e) {
                    Log.w(TAG, "pin/display : " + e);
                    ok = false;
                }
                final boolean fin = ok;
                ui.post(new Runnable() { @Override public void run() { rappel.recu(fin); } });
            }
        });
    }

    /** Appairage, étape 2 : échange le code contre un jeton (null si refusé). */
    void verifierCode(final String code, final Resultat<String> rappel) {
        fil.execute(new Runnable() {
            @Override public void run() {
                String nouveau = null;
                try {
                    String r = requete("POST", "/v1/FireTV/pin/verify", "{\"pin\":\"" + code + "\"}", false);
                    nouveau = new JSONObject(r).optString("description", null);
                    if (nouveau != null && !nouveau.isEmpty()) jeton = nouveau;
                } catch (Exception e) {
                    Log.w(TAG, "pin/verify : " + e);
                }
                final String fin = nouveau;
                ui.post(new Runnable() { @Override public void run() { rappel.recu(fin); } });
            }
        });
    }

    /** Vérifie la liaison (et relance le service s'il dort). */
    void verifier() {
        fil.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    requete("GET", "/v1/FireTV", null, true);
                    ok = true;
                } catch (Exception e) {
                    try {
                        demarrerService();
                        requete("GET", "/v1/FireTV", null, true);
                        ok = true;
                    } catch (Exception e2) {
                        ok = false;
                    }
                }
                signaler(ok);
            }
        });
    }

    // ---------- Transport ----------

    private void poster(final String chemin, final String corps) {
        fil.execute(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    requete("POST", chemin, corps, true);
                } catch (Exception e) {
                    try {
                        demarrerService();
                        requete("POST", chemin, corps, true);
                    } catch (Exception e2) {
                        Log.w(TAG, chemin + " : échec " + e2);
                        signaler(false);
                        return;
                    }
                }
                Log.i(TAG, "FireTV " + chemin + " " + (System.currentTimeMillis() - t0) + " ms");
                signaler(true);
            }
        });
    }

    private void signaler(final boolean ok) {
        ui.post(new Runnable() { @Override public void run() { if (ecouteur != null) ecouteur.connecte(ok); } });
    }

    private void demarrerService() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":8009/apps/FireTVRemote").openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(3000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/plain; charset=\"utf-8\"");
        try (OutputStream o = c.getOutputStream()) {
            o.write('x');
        }
        c.getResponseCode();
        c.disconnect();
        Thread.sleep(1500); // le temps que le port 8080 s'ouvre
    }

    private String requete(String methode, String chemin, String corps, boolean avecJeton) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + ip + ":8080" + chemin).openConnection();
        c.setSSLSocketFactory(ssl);
        c.setHostnameVerifier(toutHote);
        c.setConnectTimeout(2000);
        c.setReadTimeout(4000);
        c.setRequestMethod(methode);
        c.setRequestProperty("x-api-key", API_KEY);
        if (avecJeton && jeton != null) c.setRequestProperty("x-client-token", jeton);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (corps != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(corps.getBytes(StandardCharsets.UTF_8));
            }
        } else if ("POST".equals(methode)) {
            c.setFixedLengthStreamingMode(0);
            c.setDoOutput(true);
            c.getOutputStream().close();
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
        }
        if (code != 200) throw new Exception("HTTP " + code);
        return b.toString("UTF-8");
    }
}
