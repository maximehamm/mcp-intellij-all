# TODO

## High priority

- **get_gradle_tasks** — List available Gradle tasks for the project
- **run_gradle_task** — Execute a specific Gradle task

## New tools

- **find_references** — Find all usages of a symbol across the project
- **get_type_hierarchy** — Get inheritance hierarchy for classes
- **get_call_hierarchy** — Who calls this method, and what does it call
- **get_todo_items** — List TODO/FIXME comments across the project

- **undo**

- **add_bookmark** — Add a bookmark on a file/line
- **get_bookmarks** — List all bookmarks in the project
- **navigate_to_bookmark** — Navigate to a bookmark by name/mnemonic

- **get_implementations** — Find implementations of an interface or abstract class

## VCS — outils manquants

### Actions Git (write)
- ~~**vcs_commit**~~ ✅ 2.12.0
- ~~**vcs_stage_files**~~ ✅ 2.12.0
- ~~**vcs_push**~~ ✅ 2.12.0
- ~~**vcs_pull**~~ ✅ 2.12.0
- ~~**vcs_stash**~~ ✅ 2.12.0
- **vcs_create_branch** — créer et switcher sur une nouvelle branche
- **vcs_checkout_branch** — switcher de branche

### Merge / Rebase
- **vcs_merge_branch** — merger une branche dans la courante (via API IntelliJ)
- **get_vcs_conflicts** — lister les fichiers en conflit de merge avec leur contenu
- rebase ?

### Diff enrichi
- **get_vcs_file_history** — historique des commits qui ont touché un fichier précis
- **get_vcs_diff_between_branches** — diff entre deux branches ou deux commits
- **vcs_show_commit** — contenu complet d'un commit (message + diff de tous les fichiers)

### Pull Requests (GitHub / GitLab)
- **list_pull_requests** — PR ouvertes sur la branche (via plugin GitHub/GitLab si installé)
- **get_pull_request_comments** — lire les review comments d'une PR
