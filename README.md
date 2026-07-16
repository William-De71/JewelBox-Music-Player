# JewelBox Music Player — Android

Client Android natif du serveur **JewelBox Music Library**. **Ne fait pas de scan
local** : il consomme l'API du serveur sur le réseau (LAN ou VPN). Le serveur vit dans
un dépôt séparé (`JewelBox-Music-Library`).

## Fonctionnalités

- **Réglages** : saisie de l'adresse du serveur (`http://ip:3001`), bouton *Tester*
  (`GET /api/health`), persistance via DataStore.
- **Albums** : liste en grille depuis `GET /api/albums` (collection uniquement, triée
  par artiste puis par date) avec pochettes (Coil). Regroupement repliable par artiste.
- **Détail d'album** (`GET /api/albums/:id`) : pochette, métadonnées et liste des pistes.

Pas encore de lecture audio (Media3/ExoPlayer viendra dans une itération suivante).

## Pile technique

Kotlin · Jetpack Compose (Material 3) · Retrofit + OkHttp + kotlinx.serialization ·
Coil · DataStore. `minSdk 26`, `targetSdk 35`, `compileSdk 35`.

## Construire et lancer

1. Ouvrir **ce dossier** dans **Android Studio** (Ladybug ou plus récent).
   Il installera le SDK Android et synchronisera Gradle automatiquement.
2. Démarrer le serveur JewelBox (écoute sur `0.0.0.0:3001`).
3. **Run ▶** sur un émulateur ou un téléphone du même réseau.
4. Dans l'app : *Réglages* → saisir l'adresse du serveur → *Tester* → *Enregistrer*.

### Quelle adresse serveur saisir ?

- **Téléphone réel** (même Wi-Fi) : l'IP LAN de la machine serveur, ex. `http://192.168.1.20:3001`.
- **Émulateur Android** : la machine hôte est `http://10.0.2.2:3001` (pas `localhost`).
- **En mobilité** : l'adresse du serveur via le VPN.

Le trafic est en clair (HTTP) sur le LAN — `usesCleartextTraffic="true"` est activé exprès.
