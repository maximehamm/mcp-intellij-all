# TODO — MCP Server Companion

## Idées de nouveaux outils

### Debug (priorité haute)

- **`get_debug_stacktrace`** — Retourner les frames de la call stack avec fichier et numéro de ligne.
  Actuellement `get_debug_variables` ne donne que les variables du frame courant.

- **`evaluate_expression`** — Évaluer une expression dans le contexte de debug courant
  (ex: `myObj.getName()`, `list.size()`). Via `XDebugSession.evaluate()`.

- **`get_breakpoints`** — Lister les breakpoints actifs (fichier, ligne, condition éventuelle).

- **`set_breakpoint`** — Poser un breakpoint sur une ligne donnée d'un fichier.

- **`remove_breakpoint`** — Supprimer un breakpoint existant.

### Tests (priorité haute)

- **`get_test_results`** — Résultats du dernier run de tests : passed/failed, messages d'erreur,
  durée. Via `AbstractTestProxy` / `SMTestProxy`.

### VCS (priorité moyenne)

- **`get_git_diff`** — Diff des fichiers modifiés non committés. Via `ChangeListManager`.

- **`get_git_log`** — Historique des commits récents sur la branche courante.

### IDE (priorité basse)

- **`get_notifications`** — Lire les notifications/ballons affichés par l'IDE
  (inspections, erreurs de config, etc.).
