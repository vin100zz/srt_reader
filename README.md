# SRT Reader

Affiche un fichier de sous-titres `.srt` par-dessus n'importe quelle appli vidéo de Google TV
(Netflix, Canal+, Prime Video…), et se pilote depuis le navigateur du téléphone.

- **Sur la TV** : une fenêtre transparente en bas de l'écran affiche la réplique en cours.
  Elle ne prend jamais le focus, donc la télécommande continue de piloter Netflix.
- **Sur le téléphone** : une page web (adresse et QR code affichés sur la TV) pour envoyer le
  `.srt`, faire Lecture/Pause et recaler les sous-titres.

## Contenu

| Dossier / fichier | Rôle |
|---|---|
| `core/` | Logique en Kotlin pur : lecture des `.srt`, horloge et recalage, serveur web, client OpenSubtitles, tests |
| `core/src/main/resources/web/index.html` | La page de contrôle (HTML/CSS/JS, un seul fichier) |
| `app/` | L'appli Google TV : écran d'accueil, service, fenêtre de sous-titres |
| `build.cmd` | Lance les tests et compile `dist\srt-reader.apk` |
| `install.cmd` | Installe l'APK sur la TV et donne l'autorisation d'affichage |
| `dev.cmd` | Lance la page de contrôle sur le PC, sans TV |

Outils utilisés sur le PC : le JDK 21 déjà installé (`C:\Program Files\Eclipse Adoptium\jdk-21…`)
et le SDK Android en ligne de commande dans `%LOCALAPPDATA%\Android\Sdk`. Pas d'Android Studio.

## 1. Compiler

Double-clic sur `build.cmd`, ou dans un terminal :

```
build.cmd
```

Résultat : `dist\srt-reader.apk`.

## 2. Préparer la TV (une seule fois)

1. **Mode développeur** : Paramètres > Système > À propos > clique 7 fois sur
   « Build du système d'exploitation Android TV ».
2. Paramètres > Système > **Options pour les développeurs** > active **Débogage USB**
   (malgré son nom, il sert aussi au débogage par le réseau).
3. Note l'**adresse IP** de la TV : Paramètres > Réseau et Internet > ton réseau.

Sur les Google TV plus récentes, il n'y a parfois que **Débogage sans fil** : active-le, choisis
« Associer l'appareil avec un code », puis depuis le PC :

```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe pair IP_DE_LA_TV:PORT_D_ASSOCIATION
```

et utilise ensuite le port affiché sur l'écran « Débogage sans fil » (différent de celui
d'association) dans la commande d'installation.

## 3. Installer

```
install.cmd 192.168.1.42
```

(ou `install.cmd 192.168.1.42:41234` en débogage sans fil). La première fois, la TV demande
d'autoriser le PC : accepte. Le script installe l'appli, lui donne le droit de s'afficher par-dessus
les autres applis et l'accès aux notifications (pour la synchro automatique), puis l'ouvre.

Après une mise à jour, rouvre l'appli sur la TV : l'installation arrête le service des sous-titres.

Si l'appli dit qu'il manque une autorisation, lance depuis le PC :

```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe shell appops set fr.srtreader SYSTEM_ALERT_WINDOW allow
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe shell cmd notification allow_listener fr.srtreader/fr.srtreader.MediaListener
```

## 4. Utiliser

1. Ouvre **SRT Reader** sur la TV. L'écran affiche une adresse (ex. `http://192.168.1.42:8080`)
   et un QR code : ouvre-la sur le téléphone, connecté au même réseau.
2. Sur le téléphone, **Choisir un .srt**, ou cherche-le directement dans
   **Trouver des sous-titres anglais** (voir plus bas).
3. Lance le film dans Netflix, Canal+… et **coupe leurs propres sous-titres**.
4. Sur le téléphone, **Lecture**. Si « Lecture/Pause pilote aussi l'appli vidéo » est coché,
   le film démarre ou s'arrête en même temps que les sous-titres.
5. Quand la première réplique est prononcée, appuie sur **C'est dit maintenant**. Tu peux aussi
   toucher n'importe quelle réplique de la liste au moment où elle est dite, ou la chercher.

**Synchro automatique** : quand l'appli vidéo publie sa position de lecture (c'est le cas de
Netflix), la page affiche « Synchro automatique avec Netflix ». Les sous-titres suivent alors
tout seuls la lecture, les pauses et les sauts faits à la télécommande : il ne reste qu'à recaler
une fois avec « C'est dit maintenant ». Sinon, l'appli garde sa propre horloge (« Synchro manuelle »).
Le suivi peut être coupé dans « Affichage sur la TV ».

Ensuite :

- **Sous-titres −0,5 s / +0,5 s** : petits ajustements si ça arrive trop tôt ou trop tard.
- **Film −10 s / +10 s / ±1 min** (synchro manuelle seulement) : à utiliser quand tu avances ou
  recules dans l'appli (Netflix saute de 10 s par appui), pour que les sous-titres suivent.
- **Vitesse** : si le décalage grandit au fil du film, c'est que le `.srt` a été fait pour une
  version à une autre cadence (souvent 25 i/s contre 23,976). Recale une deuxième fois au moins
  15 minutes plus loin : la vitesse est corrigée automatiquement. Tu peux aussi la choisir dans la liste.
- **Affichage sur la TV** : taille, hauteur et fond du texte.

L'état (fichier, décalage, vitesse, réglages) est conservé si la TV redémarre l'appli.

## Trouver des sous-titres sur OpenSubtitles

La page du téléphone peut chercher des sous-titres anglais sur [OpenSubtitles](https://www.opensubtitles.com)
et les charger directement sur la TV.

1. Crée un compte gratuit sur opensubtitles.com, puis une **clé d'API** dans la rubrique
   « API consumers » de ton profil.
2. Sur le téléphone, en bas de la page, **Compte OpenSubtitles** : colle la clé, puis
   **Enregistrer sur la TV**. L'identifiant et le mot de passe sont facultatifs : ils augmentent
   le nombre de téléchargements autorisés par jour.
3. **Trouver des sous-titres anglais** : tape le titre (et la saison et l'épisode pour une série),
   puis touche le résultat voulu. Il est téléchargé et chargé tout de suite.

Les résultats affichent la cadence (i/s), le nombre de téléchargements et ces repères :
**Copie Netflix** (fait à partir de la version Netflix, donc calé dessus, et placé en tête
quand tu regardes Netflix), **Malentendants** (bruits décrits entre crochets) et
**Traduction auto** (placée en dernier). Si l'appli vidéo publie le titre en cours (Canal+ ?),
il pré-remplit la recherche ; Netflix ne le publie pas.

La clé, l'identifiant et le mot de passe sont gardés dans les données privées de l'appli sur la
TV, et transmis du téléphone à la TV sur le réseau local, sans chiffrement.

## Tester ou modifier la page sur le PC

```
dev.cmd
```

puis ouvre <http://localhost:8080>. La page est relue à chaque rechargement : modifie
`core/src/main/resources/web/index.html` et recharge le navigateur. Le texte qui serait affiché
sur la TV s'écrit dans la console. La clé OpenSubtitles saisie en test est gardée dans
`core/build/dev-data`.

## Compatibilité

- **Netflix** (Google TV Streamer) : sous-titres au-dessus du film, Lecture/Pause depuis le
  téléphone et position de lecture publiée vérifiés.
- **Canal+** : position de lecture publiée et suivie automatiquement (vérifié).
- **Prime Video** : pas encore testé. Pour voir si une appli publie sa position, lance
  un film puis :

  ```
  %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe shell dumpsys media_session
  ```

- **Sécurité** : la page n'a pas de mot de passe. Toute personne sur ton réseau local peut
  piloter les sous-titres (et rien d'autre).
