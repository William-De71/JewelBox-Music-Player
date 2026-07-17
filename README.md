# JewelBox Music Player — Android

Client Android natif du serveur **JewelBox Music Library**. **Ne fait pas de scan
local** : il consomme l'API du serveur sur le réseau (LAN ou VPN). Le serveur vit dans
un dépôt séparé (`jewelbox-music-library`).

## Fonctionnalités

- **Bibliothèque** : collection possédée uniquement (la liste de souhaits est exclue),
  triée par artiste puis par année, avec regroupement repliable par artiste
  (menu ⋮ → « Grouper par artiste », tout replier / tout déplier).
- **Détail d'album** : pochette, métadonnées, liste des pistes (les pistes sans fichier
  audio sont estompées).
- **Lecture en streaming** (Media3/ExoPlayer + `MediaSessionService`) : lecture en fond,
  notification média avec contrôles sur l'écran verrouillé, gestion de l'audio focus,
  pause au débranchement du casque.
- **Mini-player** (toutes les vues) + **grand player** : seek, précédent/suivant,
  shuffle, repeat (off/tout/une), swipe gauche/droite pour changer de piste.
- **Scrobbling Last.fm** + compteur de lectures : mêmes règles que la PWA ; la session
  Last.fm est stockée côté serveur et partagée avec elle (rien à configurer dans l'app).
- **Réglages** : adresse du serveur (`http://ip:3001`), bouton *Tester* (`GET /api/health`),
  persistée via DataStore.
- Tous les textes UI sont dans `app/src/main/res/values/strings.xml` (français par
  défaut) — une traduction = un dossier `values-<lang>/` avec les mêmes clés.

## Pile technique

Kotlin · Jetpack Compose (Material 3) · Media3 (ExoPlayer + session) · Retrofit + OkHttp
+ kotlinx.serialization · Coil · DataStore. `minSdk 26`, `targetSdk 35`, `compileSdk 35`.

## Architecture (survol)

```
app/src/main/kotlin/com/jewelbox/player/
├── JewelBoxApp.kt          Application + ServiceLocator (DI manuel)
├── MainActivity.kt         Activité unique, hôte Compose
├── data/                   Couche données
│   ├── net/                Retrofit : ApiClient, JewelBoxApi (endpoints), Dtos
│   ├── AlbumRepository.kt  Façade API (albums, santé, scrobble…)
│   ├── ServerPrefs.kt      DataStore : URL du serveur
│   └── CoverUrl.kt         Résolution des pochettes (absolue vs /covers/…)
├── playback/               Lecture
│   ├── PlaybackService.kt  MediaSessionService qui héberge ExoPlayer
│   └── PlayerConnection.kt Pont UI ↔ service (StateFlow) + scrobbling
└── ui/                     Compose (un dossier par écran : Screen + ViewModel)
    ├── nav/AppNav.kt       Routes de navigation
    ├── theme/              Material 3
    ├── albums/  albumdetail/  player/  settings/
```

MVVM léger : chaque écran collecte le `StateFlow` de son ViewModel (état descendant,
événements remontants). La lecture est l'exception : son état vit dans le service
(source de vérité = ExoPlayer) et `PlayerConnection` l'expose à toute l'UI.

## Développement

### Builder et tester sur un téléphone

Prérequis : SDK Android (`~/Android/Sdk`) et un JDK 17-21 (pas le JDK 25 système —
le script utilise celui d'Android Studio). Téléphone branché en USB avec le débogage
activé (sur Xiaomi/MIUI : activer aussi « Installer via USB »).

```bash
./run-phone.sh          # build + installe + lance sur le téléphone branché
./run-phone.sh --logs   # idem, puis affiche les logs de l'app
```

Le build de dev a son propre application id (`com.jewelbox.player.debug`) : il
s'installe **à côté** de l'app release (« JewelBox dev » vs « JewelBox » au
lanceur), donc aucune collision de signatures avec l'APK des Releases GitHub.

Dans l'app : *Réglages* → adresse du serveur → *Tester* → *Enregistrer*.

- **Téléphone réel** (même Wi-Fi) : IP LAN de la machine serveur, ex. `http://192.168.1.20:3001`.
- **Émulateur** : la machine hôte est `http://10.0.2.2:3001`.
- Le trafic est en clair (HTTP) sur le LAN — `usesCleartextTraffic="true"` est voulu.

### Workflow git

Jamais de commit direct sur `main` : branche (`feat/…`, `fix/…`) → push → **pull
request** → merge. Chaque PR déclenche un build de contrôle (`android-pr.yml`).

## Release

Créer un tag de version sur `main` :

```bash
git tag v0.2.0 && git push origin v0.2.0
```

`android-release.yml` construit alors un **APK signé** et le publie en **GitHub
Release**. La version de l'app vient du tag (`versionName`) et du numéro de run
(`versionCode`). Secrets requis dans le dépôt : `RELEASE_KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Installation : télécharger l'APK de la Release sur le téléphone — ou utiliser
[Obtainium](https://github.com/ImranR98/Obtainium) pointé sur ce dépôt pour recevoir
les mises à jour automatiquement.
