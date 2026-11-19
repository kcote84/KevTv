# KevTV 0.1.0 : décisions techniques

## Point de départ

Lecteur direct dérivé du guide et du catalogue VickTV, avec commandes de lecture adaptées de VickTV2. Le projet utilise un nouveau dépôt sans historique ni configuration des projets d’origine. Son identifiant Android est ca.kevtv.

## Architecture

- **MainActivity / MediaScreen / TvGuideScreen** : menus français, guide EPG, filtres, favoris, affiches, saisons, reprise et mini-guide. Graphite, bleu électrique et focus visible au D-pad. Les gestes sur le petit écran se font en mode paysage.
- **Account / Store** : un seul profil validé par `player_api.php`; chiffrement AES-GCM avec clé Android Keystore. Ni les mots de passe, ni le serveur ne sont codés en dur. Le système ne sauvegarde ni ne transfère les préférences vers d’autres appareils. Les affichettes contenant une signature ou des identifiants ne sont pas enregistrées.
- **Xtream / ProviderDataSource** : requêtes GET directes; l’adresse sans schéma prend HTTPS. HTTP nécessite une saisie explicite et un avertissement. Aucun certificat n'est ignoré. Les requêtes de catalogue et de lecture autorisent jusqu’à trois redirections sur la même origine (protocole, domaine et port). La lecture ne transmet pas les codes Xtream à un autre domaine ou port. Les fournisseurs qui imposent un serveur vidéo distinct peuvent donc authentifier l'utilisateur et afficher le catalogue sans pouvoir lire certaines vidéos. Une erreur ne doit jamais révéler une URL de flux ni le corps d'une réponse.
- **Media3** : lecture du direct, HLS ou MPEG-TS selon réglage, films et épisodes. L’option HLS est un format de lecture pour la chaîne sélectionnée, et **pas** un import de liste M3U. La disponibilité des codecs et d’une piste française ou sans narration varie selon appareil et source. Le mode Stéréo mélange localement le PCM que l’appareil peut décoder vers deux canaux, y compris les voix centrales. Il couvre les dispositions habituelles de 1 à 8 canaux, sans bibliothèque FFmpeg embarquée; un codec absent sur l’appareil n’est pas ajouté. Automatique et Cinéma / 5.1 gardent le comportement de Media3. Pas d’adaptation du son par un PC.
- **Données locales** : favoris et historique en stockage privé; aucune synchronisation maison. Un remplacement de profil réussi supprime le contenu du profil précédent. La lecture n’est pas une diffusion de l’appareil vers un autre.
- Aucun workflow GitHub Actions, service Windows, installation ADB sur les appareils domestiques, clé de signature de production, secret ou catalogue utilisateur n’est nécessaire pour travailler sur ce projet.

## Validation

Les tests unitaires ciblent les règles du catalogue, les filtres, l’EPG, les saisons/épisodes, la progression, la sélection audio, les erreurs et les règles de sécurité du transport. Les tests doivent utiliser uniquement des comptes et contenus fictifs. Ne jamais utiliser les comptes du serveur Maison pour un essai sur la route.

Le script facultatif [d’essai Android](../qa/README.md) utilise un émulateur jetable. Une compilation ou un test local ne prouve pas qu’un abonnement réel, le réseau hôtelier ou un modèle particulier de Fire Stick sera compatible.

## Signature et mises à jour

Sur le PC de publication Windows, exécuter tools/sign-release.ps1 après assembleRelease; il produit build/livrables/KevTV.apk et vérifie la signature avec Android Build Tools. La clé de diffusion est dédiée à KevTV, hors dépôt. Son mot de passe aléatoire est protégé avec Windows DPAPI pour le compte Windows qui l’a créée. Ne jamais renvoyer cette clé, son fichier de déverrouillage ni un jeton dans un dépôt, des journaux ou des pièces jointes.

L’initialisation (paramètre -Initialize) ne doit être faite qu’une fois, avant la toute première publication. Ne jamais recréer une clé pour réparer une installation existante. Prévoir une sauvegarde privée récupérable du matériel de signature et du profil/secret de récupération Windows. Copier uniquement les deux fichiers sur un autre PC ne garantit pas leur déchiffrement. La perte du certificat privé empêche la mise à jour des installations déjà signées.

Le contrôle public des mises à jour ne se déclenche que par le menu des réglages. Il accepte exclusivement la version stable publiée par kcote84/KevTv, l’asset KevTV.apk et les hôtes de distribution GitHub autorisés en HTTPS. Avant d’ouvrir l’installateur, le lecteur vérifie la taille, l’empreinte SHA-256 si GitHub la fournit, le nom du paquet, la correspondance avec l’étiquette de version, un versionCode strictement supérieur et le même certificat que l’application installée.

## Vérifications de la version 0.1.0

78 tests unitaires répartis en 16 suites réussis, aucune erreur lint, compilation release effectuée avec le SDK Android 35. APK vérifié avec les signatures v2 et v3; le certificat de diffusion est distinct du certificat debug. Empreinte SHA-256 du certificat public : 23018cef9f53554f9fb6a365ced63666c465be2e3f507d0d6b899d9ba39c40d1. Aucune chaîne commerciale ni aucun compte fournisseur n’ont été utilisés pour ces essais.
