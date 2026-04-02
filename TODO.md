# TODO — MCP Server Companion

## Idées de nouveaux outils

### Debug (priorité haute)

- **`get_debug_stacktrace`** — Retourner les frames de la call stack avec fichier et numéro de ligne.
  Actuellement `get_debug_variables` ne donne que les variables du frame courant.

- **`evaluate_expression`** — Évaluer une expression dans le contexte de debug courant
  (ex: `myObj.getName()`, `list.size()`). Via `XDebugSession.evaluate()`.

### VCS (priorité moyenne)

- **`get_git_diff`** — Diff des fichiers modifiés non committés. Via `ChangeListManager`.

- **`get_git_log`** — Historique des commits récents sur la branche courante.

### IDE (priorité basse)

- **`get_notifications`** — Lire les notifications/ballons affichés par l'IDE
  (inspections, erreurs de config, etc.).

## Améliorations du plugin

### Settings (priorité moyenne)

- **Page de configuration** — Ajouter une page dans `Settings > Tools > MCP Server Companion`
  listant tous les outils exposés avec une case à cocher par outil pour les activer/désactiver.
  Les outils décochés ne sont plus enregistrés dans le `McpToolset`.
