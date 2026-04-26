Lance la suite de tests d'intégration profonde sur les **72 tools** MCP, dans le sandbox.

Ce mode opératoire est utilisé pour valider tout le plugin avant un gros changement
(ex : refactor de packages, migration de version IntelliJ, modification d'un toolset).

## Pré-requis

- Le sandbox `runIde` doit être démarré sur le **port 64343**
- Si le sandbox n'est pas prêt, lance d'abord `/run`
- Le sandbox doit avoir Git initialisé avec au moins 1 commit (pour les tests VCS)

## Étape 1 — Vérifier que le sandbox est prêt

```bash
PORT_SANDBOX=64343
curl -s -N --max-time 10 http://127.0.0.1:$PORT_SANDBOX/sse > /tmp/sse_check.txt &
sleep 2
SESSION=$(grep -o 'sessionId=[^"& \r]*' /tmp/sse_check.txt | head -1 | cut -d= -f2 | tr -d '\r\n')
[ -n "$SESSION" ] && echo "✅ Sandbox up" || echo "❌ Sandbox not reachable"
kill %1 2>/dev/null
```

Si pas prêt → invoke `/run` puis attendre 30 s.

## Étape 2 — Lancer le test approfondi

```bash
cd /Users/maxime/Development/mcp-intellij-all
python3 scripts/deep-test-all-tools.py
```

Le script :
1. Configure les pré-requis (breakpoints, build, gradle, terminal, run config)
2. Pour chaque tool, appelle avec des **paramètres aux noms exacts** (vérifiés contre les signatures Kotlin)
3. Vérifie pour chaque réponse :
   - `isError == false`
   - réponse non vide
   - pattern attendu (clé JSON, mot-clé, statut "success", etc.)
4. Imprime un résumé : `✓ X / 73   ✗ Y` puis détaille les échecs

## Étape 3 — Analyse des échecs

**Pour chaque tool en échec (✗) :**

1. Lis `reason` dans la sortie — c'est généralement un de :
   - `isError=true` : le tool a planté ou rejeté l'appel (souvent param manquant)
   - `missing 'xxx'` : la réponse ne contient pas le pattern attendu
   - `JSON missing key 'xxx'` : la réponse n'est pas un JSON conforme
   - `transport: …` : le sandbox n'a pas répondu

2. **Récupère le message exact** de l'erreur via une nouvelle invocation (le script a un mode debug : utilise un `inspect` similaire à `scripts/deep-test-all-tools.py`).

3. **Distingue** :
   - **Erreur d'appel** (mauvais nom de paramètre, args manquants) → corrige les args dans le script et relance
   - **Bug fonctionnel** dans le tool → c'est une vraie régression, à corriger dans le code Kotlin

4. **Cas connus** où le tool retourne une chaîne très courte mais valide :
   - `replace_text_undoable` → `"ok"`
   - `clear_highlights` → `"cleared"` ou similaire
   - Tools avec args manquants → message `disabled` (cas normal pour les disabled-by-default)

## Étape 4 — Mise à jour du script

Si tu découvres un nouveau cas non couvert (nouveau tool, paramètre renommé, validator trop strict),
**modifie `scripts/deep-test-all-tools.py`** et commit la modification.

## Étape 5 — Rapport final

Affiche en synthèse :
- Nombre de tests OK / total
- Liste des bugs réels (à corriger dans le code)
- Liste des erreurs d'appel (corrigées dans le script)
- Note sur les tools nécessitant une vérification manuelle (ex: visuel : `show_diff`, `highlight_text`)

## Notes importantes

- **Ne te satisfais pas** d'une réponse vide — c'est un signal qu'il y a quelque chose qui cloche
- **Distingue** comportement attendu (ex: `"No conflicts to resolve"` pour `vcs_open_merge_tool` sans conflit) vs vrai bug
- **Toujours utiliser les noms de paramètres EXACTS** — la plupart des "erreurs" précédentes venaient de mauvais noms (`name` au lieu de `configurationName`, `commitHash` au lieu de `hash`, etc.)
