package fr.djamel.salon;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.Normalizer;

import javax.crypto.Cipher;

/**
 * Client ADB minimal (protocole réseau d'adbd, port 5555), pour taper du texte sur la Fire TV.
 * La première connexion fait apparaître sur la TV « Autoriser le débogage ? » : il faut accepter
 * (case « Toujours autoriser ») ; ensuite la clé du téléphone est reconnue.
 * Une seule commande à la fois (synchronized), sur le fil de l'appelant.
 *
 * Piège Fire OS 5 : adbd n'accepte qu'UN hôte TCP à la fois (le suivant reçoit « Connection reset »).
 * On libère donc la liaison après 20 s sans commande, pour que le PC puisse s'y connecter.
 */
final class Adb {
    static final String TAG = "Salon";

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAXDATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** Préfixe DigestInfo SHA-1 : adbd vérifie la signature comme celle d'une empreinte SHA-1. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    enum Etat { DECONNECTE, EN_ATTENTE_AUTORISATION, CONNECTE }

    String hote;
    int port = 5555;
    volatile Etat etat = Etat.DECONNECTE;
    private final File dossierCle;
    private KeyPair cle;
    private Socket socket;
    private DataInputStream entree;
    private OutputStream sortie;
    private int prochainId = 1;

    Adb(String hote, File dossierCle) {
        this.hote = hote;
        this.dossierCle = dossierCle;
    }

    // ---------- API ----------

    private static final long INACTIVITE = 20000;
    private final java.util.Timer minuteur = new java.util.Timer("adb-inactivite", true);
    private java.util.TimerTask liberation;

    /** Lance une commande shell et renvoie sa sortie. */
    synchronized String shell(String commande) throws Exception {
        if (liberation != null) liberation.cancel();
        try {
            return executer(commande);
        } catch (java.net.SocketException e) {
            if (etat != Etat.CONNECTE && "Connection reset".equals(e.getMessage())) {
                fermer();
                throw new Exception("la Fire TV est déjà reliée à un autre appareil en ADB (le PC ?)");
            }
            fermer();
            return executer(commande);
        } catch (Exception e) {
            boolean attente = etat == Etat.EN_ATTENTE_AUTORISATION;
            fermer();
            if (attente) throw e; // réessayer ferait apparaître une 2e demande sur la TV
            return executer(commande); // liaison perdue (TV redémarrée, veille…) : une seule nouvelle tentative
        } finally {
            liberation = new java.util.TimerTask() {
                @Override public void run() { fermer(); }
            };
            minuteur.schedule(liberation, INACTIVITE);
        }
    }

    /** Tape du texte dans le champ qui a le focus sur la TV (lent : ~1,5 s, mais sans clavier virtuel). */
    void taper(String texte) throws Exception {
        shell("input text " + pourInputText(texte));
    }

    // ---------- Flux permanent vers le clavier virtuel « amzkeyboard » ----------
    // « exec: » (Android 5+) lance la commande sans terminal : les octets passent tels quels.
    // On écrit des événements d'entrée Linux bruts dans /dev/input/eventN : quelques ms par touche.

    private int fluxLocal, fluxDistant;
    private String clavier;

    /** Écrit des événements d'entrée bruts sur le clavier virtuel de la Fire TV. */
    synchronized void injecter(byte[] evenements) throws Exception {
        if (liberation != null) liberation.cancel();
        try {
            ecrireFlux(evenements);
        } catch (Exception e) {
            boolean attente = etat == Etat.EN_ATTENTE_AUTORISATION;
            fermer();
            if (attente || e.getMessage() != null && e.getMessage().contains("autre appareil")) throw e;
            ecrireFlux(evenements); // liaison perdue : on rouvre une fois
        } finally {
            liberation = new java.util.TimerTask() {
                @Override public void run() { fermer(); }
            };
            minuteur.schedule(liberation, INACTIVITE);
        }
    }

    private void ecrireFlux(byte[] donnees) throws Exception {
        if (etat != Etat.CONNECTE || fluxDistant == 0) ouvrirFlux();
        for (int i = 0; i < donnees.length; i += MAXDATA) {
            byte[] morceau = java.util.Arrays.copyOfRange(donnees, i, Math.min(donnees.length, i + MAXDATA));
            envoyer(WRTE, fluxLocal, fluxDistant, morceau);
            attendre(OKAY, fluxLocal); // contrôle de flux d'adb : un OKAY par WRTE
        }
    }

    private void ouvrirFlux() throws Exception {
        if (etat != Etat.CONNECTE) {
            try {
                connecter();
            } catch (java.net.SocketException e) {
                if ("Connection reset".equals(e.getMessage()))
                    throw new Exception("la Fire TV est déjà reliée à un autre appareil en ADB (le PC ?)");
                throw e;
            }
        }
        if (clavier == null) clavier = trouverClavier();
        fluxLocal = prochainId++;
        envoyer(OPEN, fluxLocal, 0, ("exec:cat > " + clavier + "\0").getBytes(StandardCharsets.UTF_8));
        Message m = attendre(OKAY, fluxLocal);
        fluxDistant = m.arg0;
        Log.i(TAG, "Flux clavier ouvert sur " + clavier);
    }

    /** Le numéro du périphérique peut changer après un redémarrage : on cherche « amzkeyboard ». */
    private String trouverClavier() throws Exception {
        String sortie = executer("getevent -pl 2>/dev/null");
        String courant = null;
        for (String ligne : sortie.split("\n")) {
            ligne = ligne.trim();
            if (ligne.startsWith("add device")) courant = ligne.substring(ligne.indexOf("/dev/"));
            else if (ligne.startsWith("name:") && ligne.contains("amzkeyboard") && courant != null) return courant.trim();
        }
        return "/dev/input/event4";
    }

    private Message attendre(int commande, int id) throws Exception {
        while (true) {
            Message m = lire();
            if (m.arg1 != id) continue;
            if (m.commande == commande) return m;
            if (m.commande == CLSE) {
                fluxDistant = 0;
                throw new Exception("flux fermé par la TV");
            }
        }
    }

    /**
     * « input text » de Fire OS 5 ne connaît que l'ASCII : on retire les accents,
     * on remplace les espaces par %s et on protège le tout pour le shell.
     */
    static String pourInputText(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[^\\x20-\\x7E]", "").replace("%", "").replace(" ", "%s");
        return "'" + t.replace("'", "'\\''") + "'";
    }

    synchronized void fermer() {
        etat = Etat.DECONNECTE;
        fluxDistant = 0;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignore) {
        }
        socket = null;
    }

    // ---------- Protocole ----------

    private String executer(String commande) throws Exception {
        if (etat != Etat.CONNECTE) connecter();
        int id = prochainId++;
        envoyer(OPEN, id, 0, ("shell:" + commande + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream resultat = new ByteArrayOutputStream();
        int distant = 0;
        while (true) {
            Message m = lire();
            if (m.arg1 != id && m.commande != CNXN) continue; // message d'un ancien flux
            if (m.commande == OKAY) {
                distant = m.arg0;
            } else if (m.commande == WRTE) {
                resultat.write(m.donnees);
                envoyer(OKAY, id, m.arg0, null);
            } else if (m.commande == CLSE) {
                envoyer(CLSE, id, distant != 0 ? distant : m.arg0, null);
                break;
            }
        }
        return resultat.toString("UTF-8");
    }

    private void connecter() throws Exception {
        fermer();
        socket = new Socket();
        socket.connect(new InetSocketAddress(hote, port), 3000);
        socket.setSoTimeout(5000);
        socket.setTcpNoDelay(true);
        entree = new DataInputStream(socket.getInputStream());
        sortie = socket.getOutputStream();
        chargerCle();
        envoyer(CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signatureEnvoyee = false;
        while (true) {
            Message m;
            try {
                m = lire();
            } catch (SocketTimeoutException e) {
                if (etat == Etat.EN_ATTENTE_AUTORISATION) throw new Exception("autorisation à accepter sur la TV");
                throw e;
            }
            Log.d(TAG, String.format("ADB reçu %08x arg0=%d (%d octets)", m.commande, m.arg0, m.donnees.length));
            if (m.commande == CNXN) {
                etat = Etat.CONNECTE;
                socket.setSoTimeout(8000);
                Log.i(TAG, "ADB connecté à " + hote);
                return;
            }
            if (m.commande == AUTH && m.arg0 == AUTH_TOKEN) {
                if (!signatureEnvoyee) {
                    envoyer(AUTH, AUTH_SIGNATURE, 0, signer(m.donnees));
                    signatureEnvoyee = true;
                } else {
                    // clé inconnue de la TV : on l'envoie, la TV affiche la demande d'autorisation
                    envoyer(AUTH, AUTH_RSAPUBLICKEY, 0, clePublique());
                    etat = Etat.EN_ATTENTE_AUTORISATION;
                    socket.setSoTimeout(60000);
                    Log.i(TAG, "ADB : autorisation demandée sur la TV");
                }
            }
        }
    }

    private static final class Message {
        int commande, arg0, arg1;
        byte[] donnees;
    }

    private void envoyer(int commande, int arg0, int arg1, byte[] donnees) throws Exception {
        int n = donnees == null ? 0 : donnees.length;
        int somme = 0;
        for (int i = 0; i < n; i++) somme += donnees[i] & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + n).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(commande).putInt(arg0).putInt(arg1).putInt(n).putInt(somme).putInt(~commande);
        if (n > 0) b.put(donnees);
        sortie.write(b.array());
        sortie.flush();
    }

    private Message lire() throws Exception {
        byte[] tete = new byte[24];
        entree.readFully(tete);
        ByteBuffer b = ByteBuffer.wrap(tete).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.commande = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int n = b.getInt();
        m.donnees = new byte[n];
        if (n > 0) entree.readFully(m.donnees);
        return m;
    }

    // ---------- Clé RSA du téléphone ----------

    private void chargerCle() throws Exception {
        if (cle != null) return;
        File prive = new File(dossierCle, "adbkey"), publique = new File(dossierCle, "adbkey.pub");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (prive.exists() && publique.exists()) {
            PrivateKey pk = f.generatePrivate(new PKCS8EncodedKeySpec(lireFichier(prive)));
            PublicKey pub = f.generatePublic(new X509EncodedKeySpec(lireFichier(publique)));
            cle = new KeyPair(pub, pk);
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        cle = g.generateKeyPair();
        ecrireFichier(prive, cle.getPrivate().getEncoded());
        ecrireFichier(publique, cle.getPublic().getEncoded());
    }

    private byte[] signer(byte[] jeton) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, cle.getPrivate());
        c.update(SHA1_PREFIX);
        return c.doFinal(jeton);
    }

    /** Format de clé publique d'adbd (android_pubkey_encode), en base64, suivi d'un nom. */
    private byte[] clePublique() {
        RSAPublicKey pub = (RSAPublicKey) cle.getPublic();
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pub.getModulus();
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ZERO.setBit(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        BigInteger x = n;
        for (int i = 0; i < 64; i++) { b.putInt(x.mod(r32).intValue()); x = x.shiftRight(32); }
        x = rr;
        for (int i = 0; i < 64; i++) { b.putInt(x.mod(r32).intValue()); x = x.shiftRight(32); }
        b.putInt(pub.getPublicExponent().intValue());
        String texte = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " salon@mimax3\0";
        return texte.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] lireFichier(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            new DataInputStream(in).readFully(b);
            return b;
        }
    }

    private static void ecrireFichier(File f, byte[] b) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
    }
}
