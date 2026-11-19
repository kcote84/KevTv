# Essai Android isolé

Sur Windows, avec Python 3 et un SDK Android local qui contient Platform-Tools, Emulator, cmdline-tools/latest et l'image système API 35 Google Play x86_64 :

    py -3.11 qa/smoke_android.py app/build/outputs/apk/debug/app-debug.apk

Avec FFmpeg déjà installé localement, ajoute `--playback` à la commande pour générer une courte vidéo synthétique et vérifier que le compteur Media3 avance. Le fichier vidéo temporaire reste dans le laboratoire.
Le script crée un nouvel appareil virtuel jetable, sans fenêtre, et utilise son propre port ADB. Il n'installe rien sur un appareil physique et n'utilise pas un AVD existant. Le serveur Xtream simulé n'écoute que la boucle locale. Les seuls identifiants utilisés sont des données d'essai, et aucune requête d'authentification n'est journalisée.

Points vérifiés : premier lancement, absence du menu d'enregistrements, refus d'un mauvais mot de passe, connexion au fournisseur simulé, affichage du catalogue, persistance du compte après redémarrage, annulation puis confirmation de l'oubli du compte. L'essai ne démontre pas la lecture chez un fournisseur réel ni la navigation sur un appareil Fire TV physique.