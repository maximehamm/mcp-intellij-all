# TODO — MCP Server Companion

## Idées de nouveaux outils

### Debug (priorité haute)

- **`get_debug_stacktrace`** — Retourner les frames de la call stack avec fichier et numéro de ligne.
  Actuellement `get_debug_variables` ne donne que les variables du frame courant.

- **`evaluate_expression`** — Évaluer une expression dans le contexte de debug courant
  (ex: `myObj.getName()`, `list.size()`). Via `XDebugSession.evaluate()`.

### IDE (priorité basse)

- **`get_notifications`** — Lire les notifications/ballons affichés par l'IDE
  (inspections, erreurs de config, etc.).

## Améliorations du plugin

