# KevTV

Lecteur Xtream Codes personnel pour Android et Fire TV. Le téléphone ou le téléviseur communique directement avec ton fournisseur; ton ordinateur peut rester fermé. KevTV a son propre nom, sa propre icône et son propre stockage. L’installer ne remplace ni VickTV ni VickTV2.

## Première ouverture

1. Ouvre **KevTV** et entre l’adresse du serveur, ton nom d’utilisateur et ton mot de passe Xtream. Entre l’adresse de base, pas un lien de liste M3U ou de flux.
2. Utilise **HTTPS** si le fournisseur l’offre. Sans préfixe, l’app essaie HTTPS. Pour un serveur seulement HTTP, il faut écrire `http://` et accepter l’avertissement : en HTTP, les identifiants et les flux ne sont pas protégés pendant le transport.
3. Une fois connecté, ouvre **Guide TV**, **Films**, **Séries** ou **Mes favoris**. Les filtres, la recherche, les fiches, les saisons et **Continuer** fonctionnent à partir du catalogue de ce compte. Les commandes acceptent le toucher et la télécommande.
4. Pour retirer le compte, ouvre **Réglages → Oublier mon compte**. Cela efface aussi les favoris, l’historique et les préférences audio propres aux contenus du compte. Pour changer de fournisseur, l’ancien compte reste en place tant que le nouveau n’a pas été validé.

L’app ne fournit ni abonnement ni chaîne. N’utiliser que des contenus et des appareils autorisés par le fournisseur. Une connexion Internet est nécessaire; aucun jumelage, serveur maison, VPN ou enregistrement n’est requis. Un seul compte est mémorisé sur l’appareil. Les favoris et la progression ne sont pas synchronisés entre appareils. Aucun import M3U, téléchargement vidéo ni enregistrement n'est proposé.

## Construire sur le PC

SDK Android 35, JDK 17 ou 21. Indiquer le chemin du SDK dans `ANDROID_HOME` si Android Studio ne le fournit pas.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Pour une installation normale, prendre l’asset **KevTV.apk** dans les [versions officielles](https://github.com/kcote84/KevTv/releases). L’APK de débogage ne doit pas être distribué à sa place. Android n’appliquera une mise à jour que si elle porte la même signature.

**Réglages → Mises à jour GitHub** vérifie sur demande si une nouvelle version stable est disponible. L’app ne télécharge rien au lancement et ne transmet aucun code Xtream à GitHub. Confirmer le téléchargement, puis choisir d’ouvrir l’installateur Android; la décision finale reste la tienne. Les préversions se trouvent sur la page des versions, mais ne sont pas proposées automatiquement par l’app.

Le script de signature Windows prépare la version de diffusion sans la publier. Pour une mise à jour, conserver le paquet et le certificat d’origine, augmenter versionCode et versionName, produire un APK signé et le joindre sous le nom **KevTV.apk** à une version stable du dépôt (exemple d’étiquette : v0.2.0). Voir les consignes de conservation de la clé dans le guide technique.

Voir [la référence technique](docs/DEVELOPPEMENT.md) et [le scénario de validation Android isolé](qa/README.md).
