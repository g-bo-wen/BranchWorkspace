# BranchWorkspace

BranchWorkspace is an IntelliJ IDEA plugin that gives each Git branch its own uncommitted workspace.

## Behavior

- When the current branch is about to change, local changes are saved as a patch.
- Patches are stored in `.idea/branch-workspace/<branch>.patch`.
- Untracked files are included in the saved patch.
- The old branch workspace is cleared before checkout without using `git stash`.
- After checkout, the target branch patch is restored and deleted on success.
- Restore is skipped with a notification if the target branch already has local changes.

## UI

- Settings: `Settings | Tools | Branch Workspace`
- ToolWindow: `Branch Workspace`

The ToolWindow lists saved workspaces and provides manual actions for saving, restoring, deleting, refreshing, and opening the storage directory.

## Development

```powershell
.\gradlew.bat compileKotlin
.\gradlew.bat verifyPluginProjectConfiguration
```
