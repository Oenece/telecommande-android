# telecommande-android

Transformer un vieux téléphone Android en télécommande de salon : la Fire TV par le Wi-Fi, la TV par infrarouge. Écrit et testé sur un Xiaomi Mi Max 3 (Android 10), mais l'appli marche sur tout téléphone Android 10 ou plus. Pour la partie TV, il faut un émetteur infrarouge.

Deux applis Java, sans Gradle ni bibliothèque.

## Salon (`salon/`)

La télécommande elle-même, en mode sombre.

- **Molette façon télécommande Siri** :
  - toucher l'anneau en haut, bas, gauche ou droite = flèches (doigt maintenu = répétition) ;
  - faire tourner le doigt sur l'anneau = avancer ou reculer dans la vidéo ;
  - centre : toucher = OK, glisser = pavé tactile, appui long = Menu.
- **Fire TV par le réseau** : navigation, Retour, Accueil, Lecture et lancement des applis, avec le service télécommande d'Amazon (celui de l'appli officielle).
  - DIAL sur le port 8009 pour démarrer le service ;
  - appairage par un code affiché sur la TV ;
  - touches envoyées en HTTPS sur le port 8080.
- **Texte et recherche** : un client ADB minimal (protocole d'adbd, port 5555) écrit directement dans le clavier virtuel de la Fire TV. On tape au clavier du téléphone, ou à la voix.
  - Il faut activer le débogage ADB sur la Fire TV, puis accepter la clé du téléphone à la première connexion.
- **TV par infrarouge** : marche/arrêt, source, volume, muet, menu, flèches. Les boutons de volume du téléphone règlent le son de la TV.

Les codes infrarouges sont ceux d'une TV Polaroid TVLED434K01 (NEC étendu, adresse `0x7201`) : voir `salon/src/fr/djamel/salon/TvIr.java` pour une autre TV.

Premier réglage depuis un PC (adresse de la Fire TV et jeton d'appairage), ou par l'appairage intégré dans l'appli :

```
adb shell am start -n fr.djamel.salon/.Salon --es ip 192.168.1.38 --es jeton XXXX
```

## Testeur IR (`testeur-ir/`)

Pour trouver les codes d'une TV inconnue.

1. L'appli envoie un par un les 315 codes « marche/arrêt » de la télécommande universelle du Flipper Zero, un toutes les 1,5 s.
2. On appuie sur Pause dès que la TV réagit.
3. Les 6 derniers codes envoyés deviennent des boutons à retester : on retrouve le bon numéro.
4. On cherche ensuite l'adresse trouvée dans Flipper-IRDB pour avoir toutes les touches de la télécommande.

`testeur-ir/outils/convertir_codes.py` convertit le fichier Flipper (`donnees/flipper_tv.ir`) en impulsions pour `ConsumerIrManager` (`assets/codes.txt`).

## Compiler

Avec le JDK 17 et le SDK Android (build-tools et platform 34) :

```
powershell -ExecutionPolicy Bypass -File salon\build.ps1 [-Install]
powershell -ExecutionPolicy Bypass -File testeur-ir\build.ps1 [-Install]
```

Les chemins des outils sont en dur en haut de chaque `build.ps1` : à adapter. La clé de signature est créée au premier build (elle n'est pas dans le dépôt).

## Sources des codes infrarouges

- Télécommande universelle TV : [flipperzero-firmware](https://github.com/flipperdevices/flipperzero-firmware) (`tv.ir`).
- Touches de la TV : [Flipper-IRDB](https://github.com/UberGuidoZ/Flipper-IRDB), `TVs/Bauhn/Bauhn_ATV_50FHD4.ir`.

Voir aussi [firetv-accueil](https://github.com/Oenece/firetv-accueil) : l'écran d'accueil maison de la Fire TV.
