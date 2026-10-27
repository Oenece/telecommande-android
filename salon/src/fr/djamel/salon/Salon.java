package fr.djamel.salon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Télécommande « Salon » : la Fire TV par le réseau, la TV Polaroid par infrarouge.
 * - Navigation, OK, Retour, Accueil, Lecture, applis : service télécommande d'Amazon (HTTPS 8080).
 * - Texte en direct, Rechercher, avance/retour rapide : clavier virtuel de la Fire TV par ADB (port 5555).
 * - TV : infrarouge. Les boutons de volume du téléphone règlent le son de la TV.
 *
 * Pilotable depuis le PC pour les tests (am start ne demande pas l'injection de touches) :
 *   am start -n fr.djamel.salon/.Salon --es ip 192.168.1.38 --es jeton XXXX   (réglage initial)
 *   am start -n fr.djamel.salon/.Salon --es cmd touche --es arg dpad_down
 *   cmd = touche (action) | tv (code hexa, ex. 1E) | lancer (paquet) | texte (contenu du champ) | valider | lecture
 *         | defiler (+1/-1) | cle (code de touche Linux) | adb
 */
public class Salon extends Activity {
    static final String TAG = "Salon";
    static final int VOIX = 1;

    /** Applis toujours proposées, même si la Fire TV ne les liste pas. */
    static final String[][] APPLIS_FIXES = {
            {"fr.djamel.accueil", "Accueil"},
    };
    /** Prime Video se referme tout seul sur Fire OS 5.2.9.5 (bug de l'appli, pas de la télécommande) ; Photos et Silk ne servent pas. */
    static final String[] APPLIS_MASQUEES = {"com.amazon.avod", "com.amazon.bueller.photos", "com.amazon.cloud9"};

    SharedPreferences reglages;
    FireTv fireTv;
    TvIr tv;
    Adb adb;
    final ExecutorService filAdb = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService images = Executors.newFixedThreadPool(2);
    float d;

    TextView pastille, etatClavier, legendeTv;
    Molette molette;

    /**
     * Mode TV : après Source, le menu affiché est celui de la TV, qui n'écoute que l'infrarouge.
     * La molette et Retour pilotent alors la TV, et on revient à la Fire TV après 10 s sans geste.
     */
    boolean modeTv;
    static final int DUREE_MODE_TV = 10000;
    final Runnable finModeTv = new Runnable() { @Override public void run() { modeTv(false); } };
    LinearLayout rangeeApplis;
    EditText recherche;

    /** Texte déjà tapé sur la TV : on n'envoie que la différence (lettres ajoutées, effacements). */
    String envoye = "";
    boolean miroirEnPause;
    volatile boolean clavierDirect = true;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        setShowWhenLocked(true); // utilisable écran verrouillé : ce n'est qu'une télécommande
        setTurnScreenOn(true);   // ouverte depuis le PC ou un raccourci : l'écran s'allume
        reveillerEcran();
        getWindow().setStatusBarColor(0xff141416);
        getWindow().setNavigationBarColor(Ui.BG);

        reglages = getSharedPreferences("salon", MODE_PRIVATE);
        lireReglages(getIntent());
        fireTv = new FireTv(reglages.getString("ip", "192.168.1.38"), reglages.getString("jeton", null));
        tv = new TvIr(this);
        adb = new Adb(fireTv.ip, getFilesDir());
        fireTv.ecouteur = new FireTv.Etat() {
            @Override public void connecte(boolean ok) { majPastille(ok); }
        };

        setContentView(construire());
        fireTv.verifier();
        chargerApplis();
        commande(getIntent());
    }

    /** setTurnScreenOn ne suffit pas sur MIUI 12 : verrou de réveil de 3 s (déprécié mais toujours actif sur Android 10). */
    @SuppressWarnings("deprecation")
    void reveillerEcran() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isInteractive()) return;
        android.os.PowerManager.WakeLock v = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "salon:reveil");
        v.acquire(3000);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        reveillerEcran();
        if (lireReglages(intent)) {
            fireTv.ip = reglages.getString("ip", fireTv.ip);
            fireTv.jeton = reglages.getString("jeton", fireTv.jeton);
            adb.hote = fireTv.ip;
            adb.fermer();
            fireTv.verifier();
            chargerApplis();
        }
        commande(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        fireTv.verifier();
    }

    boolean lireReglages(Intent i) {
        if (i == null) return false;
        SharedPreferences.Editor e = reglages.edit();
        boolean change = false;
        if (i.getStringExtra("ip") != null) { e.putString("ip", i.getStringExtra("ip")); change = true; }
        if (i.getStringExtra("jeton") != null) { e.putString("jeton", i.getStringExtra("jeton")); change = true; }
        e.apply();
        return change;
    }

    /** Commandes de test envoyées depuis le PC. */
    void commande(Intent i) {
        if (i == null || i.getStringExtra("cmd") == null) return;
        String cmd = i.getStringExtra("cmd"), arg = i.getStringExtra("arg");
        Log.i(TAG, "Commande PC : " + cmd + " " + arg);
        switch (cmd) {
            case "touche": fireTv.touche(arg); break;
            case "tv": tv.envoyer(Integer.parseInt(arg, 16)); break;
            case "lancer": fireTv.lancer(arg); break;
            case "texte": recherche.setText(arg); break;
            case "lecture": fireTv.lecturePause(); break;
            case "defiler": defiler(Integer.parseInt(arg)); break;
            case "cle": injecter(Clavier.touche(Integer.parseInt(arg)), "touche " + arg); break;
            case "valider": valider(); break;
            case "adb": autoriserClavier(); break;
        }
        i.removeExtra("cmd");
    }

    // ---------- Boutons physiques : le volume du téléphone règle la TV ----------

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP) { tv.envoyer(TvIr.VOL_UP); return true; }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { tv.envoyer(TvIr.VOL_DN); return true; }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(code, e);
    }

    // ---------- Interface ----------

    int dp(float v) { return Math.round(v * d); }

    LinearLayout.LayoutParams lp(int w, int h, float poids) {
        return new LinearLayout.LayoutParams(w, h, poids);
    }

    LinearLayout.LayoutParams lp(int w, int h, float poids, int gauche, int haut, int droite, int bas) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, poids);
        p.setMargins(dp(gauche), dp(haut), dp(droite), dp(bas));
        return p;
    }

    TextView texte(String s, float sp, int couleur, boolean moyen) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(couleur);
        t.setTypeface(moyen ? Ui.MEDIUM : Ui.REGULAR);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Touche en relief : s'enfonce au toucher (assombrie, échelle 0,96), retour haptique, répétition si « repete ». */
    View touche(View contenu, final Relief relief, final boolean repete, final Runnable action) {
        final FrameLayout f = new FrameLayout(this);
        f.setBackground(relief);
        f.addView(contenu, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        f.setHapticFeedbackEnabled(true);
        final Runnable[] boucle = new Runnable[1];
        boucle[0] = new Runnable() {
            @Override public void run() {
                action.run();
                f.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                ui.postDelayed(this, 130);
            }
        };
        f.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        relief.appuye(true);
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(Ui.PRESS).setInterpolator(Ui.EASE);
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        action.run();
                        if (repete) ui.postDelayed(boucle[0], 450);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(boucle[0]);
                        relief.appuye(false);
                        v.animate().scaleX(1).scaleY(1).setDuration(Ui.FAST).setInterpolator(Ui.EASE);
                        return true;
                }
                return false;
            }
        });
        return f;
    }

    View toucheIcone(Icone.Forme forme, int fond, boolean ronde, String description, boolean repete, Runnable action) {
        ImageView i = new ImageView(this);
        i.setImageDrawable(new Icone(forme, dp(24), Ui.TEXT));
        View b = touche(i, new Relief(Relief.Type.BOMBE, fond, ronde ? -1 : dp(Ui.R_INNER), d), repete, action);
        b.setContentDescription(description);
        return b;
    }

    View construire() {
        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        // Fond : très léger dégradé, plus clair en haut, pour que les reliefs aient une lumière
        racine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff141416, 0xff050505, Ui.BG}));
        racine.setPadding(dp(Ui.S5), dp(Ui.S2), dp(Ui.S5), dp(Ui.S3));

        // En-tête : titre, état de la Fire TV, réglages
        LinearLayout tete = new LinearLayout(this);
        tete.setGravity(Gravity.CENTER_VERTICAL);
        tete.addView(texte("Salon", 28, Ui.TEXT, true), lp(0, -2, 1));
        pastille = texte("Fire TV …", 13, Ui.TEXT2, true);
        pastille.setPadding(dp(12), dp(8), dp(12), dp(8));
        pastille.setBackground(new Relief(Relief.Type.BOMBE, Ui.CARD, -1, d));
        pastille.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pastille.setText("Fire TV …"); fireTv.verifier(); }
        });
        tete.addView(pastille, lp(-2, -2, 0));
        View reglagesBouton = toucheIcone(Icone.Forme.REGLAGES, Ui.CARD, true, "Réglages", false, new Runnable() {
            @Override public void run() { ouvrirReglages(); }
        });
        tete.addView(reglagesBouton, lp(dp(40), dp(40), 0, 8, 0, 0, 0));
        racine.addView(tete, lp(-1, dp(52), 0));

        // TV (infrarouge) : plaque en creux qui porte les touches
        LinearLayout plaqueTv = new LinearLayout(this);
        plaqueTv.setGravity(Gravity.CENTER_VERTICAL);
        plaqueTv.setPadding(dp(8), dp(8), dp(8), dp(8));
        plaqueTv.setBackground(new Relief(Relief.Type.CREUX, Ui.CARD, dp(Ui.R_CARD), d));
        plaqueTv.addView(toucheIcone(Icone.Forme.MARCHE, 0xffd70015, false, "Allumer ou éteindre la TV", false, ir(TvIr.POWER)), lp(0, dp(54), 1, 0, 0, 4, 0));
        plaqueTv.addView(toucheIcone(Icone.Forme.SOURCE, Ui.CARD2, false, "Source de la TV", false, new Runnable() {
            @Override public void run() { tv.envoyer(TvIr.SOURCE); modeTv(true); }
        }), lp(0, dp(54), 1, 4, 0, 4, 0));
        plaqueTv.addView(toucheIcone(Icone.Forme.MUET, Ui.CARD2, false, "Couper le son de la TV", false, ir(TvIr.MUTE)), lp(0, dp(54), 1, 4, 0, 4, 0));
        LinearLayout volume = new LinearLayout(this);
        volume.addView(toucheIcone(Icone.Forme.MOINS, Ui.CARD2, false, "Baisser le son", true, ir(TvIr.VOL_DN)), lp(0, -1, 1, 0, 0, 2, 0));
        volume.addView(toucheIcone(Icone.Forme.PLUS, Ui.CARD2, false, "Monter le son", true, ir(TvIr.VOL_UP)), lp(0, -1, 1, 2, 0, 0, 0));
        plaqueTv.addView(volume, lp(0, dp(54), 2, 4, 0, 0, 0));
        racine.addView(plaqueTv, lp(-1, -2, 0, 0, 4, 0, 0));
        legendeTv = texte("", 12, Ui.TEXT3, false);
        legendeTv.setPadding(dp(8), dp(8), 0, 0);
        legendeTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (modeTv) modeTv(false); }
        });
        racine.addView(legendeTv);

        // Molette : prend toute la place restante. Elle pilote la Fire TV, ou la TV en mode TV.
        molette = new Molette(this);
        molette.ecouteur = new Molette.Ecouteur() {
            @Override public void fleche(String action) {
                if (!modeTv) { fireTv.touche(action); return; }
                prolongerModeTv();
                switch (action) {
                    case "dpad_up": tv.envoyer(TvIr.UP); break;
                    case "dpad_down": tv.envoyer(TvIr.DOWN); break;
                    case "dpad_left": tv.envoyer(TvIr.LEFT); break;
                    case "dpad_right": tv.envoyer(TvIr.RIGHT); break;
                }
            }
            @Override public void ok() {
                if (modeTv) { prolongerModeTv(); tv.envoyer(TvIr.OK); } else fireTv.touche("select");
            }
            @Override public void menu() {
                if (modeTv) { prolongerModeTv(); tv.envoyer(TvIr.MENU); } else fireTv.touche("menu");
            }
            @Override public void defiler(int sens) {
                // en mode TV, la molette se tourne comme un bouton de volume
                if (modeTv) { prolongerModeTv(); tv.envoyer(sens > 0 ? TvIr.VOL_UP : TvIr.VOL_DN); } else Salon.this.defiler(sens);
            }
        };
        racine.addView(molette, lp(-1, 0, 1, 0, 8, 0, 8));

        // Retour, Accueil, Rechercher, Lecture/Pause : touches rondes, comme sur la télécommande Siri
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.addView(toucheRonde(Icone.Forme.RETOUR, "Retour", new Runnable() {
            @Override public void run() {
                if (modeTv) { tv.envoyer(TvIr.EXIT); modeTv(false); } else fireTv.touche("back");
            }
        }));
        nav.addView(toucheRonde(Icone.Forme.ACCUEIL, "Accueil", new Runnable() {
            @Override public void run() { modeTv(false); fireTv.touche("home"); }
        }));
        nav.addView(toucheRonde(Icone.Forme.LOUPE, "Rechercher sur la Fire TV", new Runnable() {
            @Override public void run() { injecter(Clavier.touche(Clavier.SEARCH), "Rechercher"); }
        }));
        nav.addView(toucheRonde(Icone.Forme.LECTURE, "Lecture ou pause", new Runnable() {
            @Override public void run() { fireTv.lecturePause(); }
        }));
        racine.addView(nav, lp(-1, -2, 0));

        // Texte : s'écrit en direct sur la TV (lettres et effacements), Entrée valide
        LinearLayout ligneTexte = new LinearLayout(this);
        ligneTexte.setGravity(Gravity.CENTER_VERTICAL);
        recherche = new EditText(this);
        recherche.setHint("Écrire sur la TV…");
        recherche.setHintTextColor(Ui.TEXT3);
        recherche.setTextColor(Ui.TEXT);
        recherche.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        recherche.setSingleLine(true);
        recherche.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        recherche.setTypeface(Ui.REGULAR); // VISIBLE_PASSWORD (pas de correction auto) impose sinon une police à chasse fixe
        recherche.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        recherche.setBackground(new Relief(Relief.Type.CREUX, Ui.CARD, -1, d));
        recherche.setPadding(dp(20), 0, dp(20), 0);
        recherche.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { if (!miroirEnPause) synchroniser(s.toString()); }
        });
        recherche.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) { valider(); return true; }
        });
        ligneTexte.addView(recherche, lp(0, dp(50), 1));
        if (getPackageManager().resolveActivity(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0) != null) {
            ligneTexte.addView(toucheIcone(Icone.Forme.MICRO, Ui.CARD2, true, "Dicter", false, new Runnable() {
                @Override public void run() { dicter(); }
            }), lp(dp(50), dp(50), 0, 8, 0, 0, 0));
        }
        racine.addView(ligneTexte, lp(-1, -2, 0, 0, 16, 0, 0));
        etatClavier = texte("Le texte s'écrit en direct sur la TV · Entrée pour valider", 12, Ui.TEXT3, false);
        etatClavier.setPadding(dp(8), dp(8), 0, 0);
        racine.addView(etatClavier);

        // Applis de la Fire TV
        HorizontalScrollView defile = new HorizontalScrollView(this);
        defile.setHorizontalScrollBarEnabled(false);
        defile.setClipToPadding(false);
        rangeeApplis = new LinearLayout(this);
        defile.addView(rangeeApplis);
        racine.addView(defile, lp(-1, dp(88), 0, 0, 14, 0, 0));

        modeTv(false);
        return racine;
    }

    void modeTv(boolean actif) {
        modeTv = actif;
        ui.removeCallbacks(finModeTv);
        if (actif) ui.postDelayed(finModeTv, DUREE_MODE_TV);
        molette.modeTv(actif);
        if (actif) {
            legendeTv.setText("La molette pilote le menu de la TV · touche ici pour revenir à la Fire TV");
            legendeTv.setTextColor(Ui.RED);
        } else {
            legendeTv.setText(tv.disponible() ? "TV par infrarouge : vise-la avec le haut du téléphone" : "Pas d'émetteur infrarouge");
            legendeTv.setTextColor(Ui.TEXT3);
        }
    }

    void prolongerModeTv() {
        ui.removeCallbacks(finModeTv);
        ui.postDelayed(finModeTv, DUREE_MODE_TV);
    }

    View toucheRonde(Icone.Forme forme, String description, Runnable action) {
        View v = toucheIcone(forme, Ui.CARD2, true, description, false, action);
        v.setLayoutParams(lp(dp(64), dp(64), 0, 10, 0, 10, 0));
        return v;
    }

    Runnable ir(final int code) {
        return new Runnable() { @Override public void run() { tv.envoyer(code); } };
    }

    Runnable fire(final String action) {
        return new Runnable() { @Override public void run() { fireTv.touche(action); } };
    }

    void majPastille(boolean ok) {
        pastille.setText(ok ? "●  Fire TV" : "●  Fire TV hors ligne");
        pastille.setTextColor(ok ? Ui.GREEN : Ui.RED);
    }

    // ---------- Clavier virtuel de la Fire TV (ADB) ----------

    /** Envoie des événements clavier à la Fire TV ; « quoi » sert au message d'erreur. */
    void injecter(final byte[] evenements, final String quoi) {
        filAdb.execute(new Runnable() {
            @Override public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    adb.injecter(evenements);
                    Log.i(TAG, "Clavier " + quoi + " " + (System.currentTimeMillis() - t0) + " ms");
                    if (!clavierDirect) {
                        clavierDirect = true;
                        ui.post(new Runnable() { @Override public void run() { majEtatClavier(null); } });
                    }
                } catch (final Exception e) {
                    Log.w(TAG, "Clavier " + quoi + " : " + e.getMessage());
                    clavierDirect = false;
                    ui.post(new Runnable() { @Override public void run() { majEtatClavier(e.getMessage()); } });
                }
            }
        });
    }

    void majEtatClavier(String erreur) {
        if (erreur == null) {
            etatClavier.setText("Le texte s'écrit en direct sur la TV · Entrée pour valider");
            etatClavier.setTextColor(Ui.TEXT3);
        } else {
            String raison = erreur.contains("autre appareil") ? "la Fire TV est occupée par le PC"
                    : erreur.contains("autorisation") ? "accepte « Autoriser le débogage » sur la TV"
                    : "la Fire TV ne répond pas en ADB";
            etatClavier.setText("Clavier direct indisponible : " + raison + ". Entrée enverra tout le texte.");
            etatClavier.setTextColor(0xffff9f0a);
        }
    }

    /** Recopie sur la TV la différence entre ce qui est tapé et ce qui a déjà été envoyé. */
    void synchroniser(String nouveau) {
        int commun = 0;
        while (commun < envoye.length() && commun < nouveau.length() && envoye.charAt(commun) == nouveau.charAt(commun)) commun++;
        int aEffacer = Clavier.longueurTapee(envoye.substring(commun));
        String aTaper = nouveau.substring(commun);
        envoye = nouveau;
        if (aEffacer == 0 && aTaper.isEmpty()) return;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] eff = Clavier.touches(Clavier.BACKSPACE, aEffacer), tap = Clavier.texte(aTaper);
        o.write(eff, 0, eff.length);
        o.write(tap, 0, tap.length);
        injecter(o.toByteArray(), "texte");
    }

    void valider() {
        if (clavierDirect) {
            injecter(Clavier.touche(Clavier.ENTER), "Entrée");
        } else {
            String s = recherche.getText().toString().trim();
            if (!s.isEmpty()) fireTv.texte(s); // secours : saisie du service Amazon (ne marche pas dans YouTube)
        }
        // On vide le champ sans effacer ce qui vient d'être validé sur la TV
        miroirEnPause = true;
        recherche.setText("");
        miroirEnPause = false;
        envoye = "";
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(recherche.getWindowToken(), 0);
    }

    /** Molette : un cran = une avance (ou un retour) rapide. Secours par le service Amazon sans ADB. */
    void defiler(int sens) {
        if (clavierDirect) injecter(Clavier.touche(sens > 0 ? Clavier.FASTFORWARD : Clavier.REWIND), sens > 0 ? "avance" : "retour");
        else fireTv.avance(sens > 0);
    }

    /** Fait apparaître (ou vérifie) l'autorisation ADB du téléphone sur la TV. */
    void autoriserClavier() {
        filAdb.execute(new Runnable() {
            @Override public void run() {
                String message;
                try {
                    adb.shell("echo ok");
                    message = "Clavier ADB autorisé";
                    clavierDirect = true;
                } catch (Exception e) {
                    message = "Clavier ADB : " + e.getMessage();
                }
                Log.i(TAG, message);
                final String m = message;
                ui.post(new Runnable() { @Override public void run() { Toast.makeText(Salon.this, m, Toast.LENGTH_LONG).show(); } });
            }
        });
    }

    // ---------- Applis ----------

    void chargerApplis() {
        fireTv.applis(new FireTv.Resultat<List<FireTv.Appli>>() {
            @Override public void recu(List<FireTv.Appli> liste) {
                List<FireTv.Appli> toutes = new ArrayList<>();
                for (String[] f : APPLIS_FIXES) {
                    FireTv.Appli a = new FireTv.Appli();
                    a.paquet = f[0];
                    a.nom = f[1];
                    toutes.add(a);
                }
                suivant:
                for (FireTv.Appli a : liste) {
                    for (String m : APPLIS_MASQUEES) if (a.paquet.equals(m)) continue suivant;
                    toutes.add(a);
                }
                rangeeApplis.removeAllViews();
                for (FireTv.Appli a : toutes) rangeeApplis.addView(tuile(a), lp(dp(72), -1, 0, 0, 0, 10, 0));
            }
        });
    }

    View tuile(final FireTv.Appli a) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout cadre = new FrameLayout(this);
        cadre.setBackground(new Relief(Relief.Type.BOMBE, a.paquet.equals("fr.djamel.accueil") ? Ui.ACCENT : Ui.CARD2, dp(16), d));
        cadre.setClipToOutline(true);
        cadre.setPadding(dp(1), dp(1), dp(1), dp(1));
        if (a.icone != null && !a.icone.isEmpty()) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setClipToOutline(true);
            GradientDrawable masque = new GradientDrawable();
            masque.setCornerRadius(dp(15));
            img.setBackground(masque);
            cadre.addView(img, new FrameLayout.LayoutParams(-1, -1));
            chargerIcone(a, img);
        } else {
            cadre.addView(texte(a.nom.substring(0, 1), 26, Ui.TEXT, true), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        t.addView(cadre, lp(dp(60), dp(60), 0));
        TextView nom = texte(nomCourt(a.nom), 11, Ui.TEXT2, false);
        nom.setGravity(Gravity.CENTER);
        nom.setSingleLine(true);
        t.addView(nom, lp(-1, -2, 0, 0, 6, 0, 0));
        final View v = t;
        t.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(Ui.PRESS).setInterpolator(Ui.EASE);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1).scaleY(1).setDuration(Ui.FAST).setInterpolator(Ui.EASE);
                }
                return false; // laisse le défilement horizontal et le clic fonctionner
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                fireTv.lancer(a.paquet);
            }
        });
        t.setContentDescription("Ouvrir " + a.nom + " sur la TV");
        return t;
    }

    static String nomCourt(String nom) {
        int i = nom.indexOf(" - ");
        if (i < 0) i = nom.indexOf(':');
        if (i < 0) i = nom.indexOf(" pour ");
        if (i < 0) i = nom.indexOf(" sur ");
        if (i < 0) i = nom.indexOf(" for ");
        return (i > 0 ? nom.substring(0, i) : nom).trim();
    }

    void chargerIcone(final FireTv.Appli a, final ImageView img) {
        final File cache = new File(getCacheDir(), a.paquet + ".png");
        images.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (!cache.exists()) {
                        try (InputStream in = new URL(a.icone).openStream(); FileOutputStream out = new FileOutputStream(cache)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                    final Bitmap bm = BitmapFactory.decodeFile(cache.getPath());
                    if (bm != null) ui.post(new Runnable() { @Override public void run() { img.setImageBitmap(bm); } });
                } catch (Exception e) {
                    Log.w(TAG, "Icône " + a.paquet + " : " + e);
                }
            }
        });
    }

    // ---------- Voix ----------

    void dicter() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Que cherches-tu ?");
        try {
            startActivityForResult(i, VOIX);
        } catch (Exception e) {
            Toast.makeText(this, "Dictée indisponible", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == VOIX && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                recherche.setText(r.get(0)); // le miroir l'écrit sur la TV
                recherche.setSelection(recherche.length());
            }
        }
    }

    // ---------- Réglages ----------

    void ouvrirReglages() {
        String[] choix = {"Appairer la Fire TV (code sur la TV)", "Autoriser le clavier sur la TV (ADB)",
                "Adresse de la Fire TV : " + fireTv.ip, "Recharger les applis"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Réglages")
                .setItems(choix, (dialog, which) -> {
                    if (which == 0) appairer();
                    else if (which == 1) autoriserClavier();
                    else if (which == 2) changerIp();
                    else chargerApplis();
                })
                .show();
    }

    void appairer() {
        fireTv.afficherCode(new FireTv.Resultat<Boolean>() {
            @Override public void recu(Boolean ok) {
                if (!ok) { Toast.makeText(Salon.this, "La Fire TV ne répond pas", Toast.LENGTH_LONG).show(); return; }
                final EditText code = new EditText(Salon.this);
                code.setInputType(InputType.TYPE_CLASS_NUMBER);
                code.setHint("Code à 4 chiffres affiché sur la TV");
                new AlertDialog.Builder(Salon.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Code affiché sur la TV")
                        .setView(code)
                        .setPositiveButton("Valider", (dlg, w) -> fireTv.verifierCode(code.getText().toString().trim(), new FireTv.Resultat<String>() {
                            @Override public void recu(String jeton) {
                                if (jeton == null) { Toast.makeText(Salon.this, "Code refusé", Toast.LENGTH_LONG).show(); return; }
                                reglages.edit().putString("jeton", jeton).apply();
                                Toast.makeText(Salon.this, "Fire TV appairée", Toast.LENGTH_SHORT).show();
                                fireTv.verifier();
                                chargerApplis();
                            }
                        }))
                        .setNegativeButton("Annuler", null)
                        .show();
            }
        });
    }

    void changerIp() {
        final EditText champ = new EditText(this);
        champ.setText(fireTv.ip);
        champ.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Adresse IP de la Fire TV")
                .setView(champ)
                .setPositiveButton("OK", (dlg, w) -> {
                    fireTv.ip = champ.getText().toString().trim();
                    adb.hote = fireTv.ip;
                    adb.fermer();
                    reglages.edit().putString("ip", fireTv.ip).apply();
                    fireTv.verifier();
                    chargerApplis();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
