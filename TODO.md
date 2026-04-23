# TODO

## High priority

- show_diff — Open IntelliJ's diff viewer to compare current content with proposed changes
- create_scratch_file — Create a scratch file with any extension and content

- **show_diff** — Open IntelliJ's diff viewer to compare current content with proposed changes

- **get_gradle_tasks** — List available Gradle tasks for the project
- **run_gradle_task** — Execute a specific Gradle task

## New tools

- **find_references** — Find all usages of a symbol across the project
- **get_type_hierarchy** — Get inheritance hierarchy for classes
- **get_call_hierarchy** — Who calls this method, and what does it call
- **get_todo_items** — List TODO/FIXME comments across the project

- **undo / redo**
- **mark_directory** — Mark a directory as source root, test root, resources, excluded, or generated

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
- ~~**vcs_create_branch**~~ ✅ 2.13.0
- ~~**vcs_checkout_branch**~~ ✅ 2.13.0

- **vcs_fetch** — mettre à jour les refs distantes sans merger (`git fetch`)
- **vcs_merge_branch** — merger une branche dans la courante (via API IntelliJ)
- **vcs_rebase** — rebaser la branche courante sur une autre (`git rebase <branch>`)
- **get_vcs_conflicts** — lister les fichiers en conflit de merge avec leur contenu

- **vcs_reset** — reculer la branche sur un commit précédent (`--soft` / `--mixed` / `--hard`)
- **vcs_revert** — créer un commit qui annule un commit existant (`git revert <hash>`)
- **vcs_cherry_pick** — appliquer un commit spécifique sur la branche courante (`git cherry-pick <hash>`)

- **vcs_delete_branch** — supprimer une branche locale ou distante - desactivé par défaut (`git branch -d` / `git push origin --delete`)

### Diff enrichi
- **get_vcs_file_history** — historique des commits qui ont touché un fichier précis
- **get_vcs_diff_between_branches** — diff entre deux branches ou deux commits
- **vcs_show_commit** — contenu complet d'un commit (message + diff de tous les fichiers)

### Pull Requests (GitHub / GitLab)
- **list_pull_requests** — PR ouvertes sur la branche (via plugin GitHub/GitLab si installé)
- **get_pull_request_comments** — lire les review comments d'une PR
