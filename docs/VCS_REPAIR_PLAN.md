# VCS_REPAIR_PLAN - keyboard-v2

Status: **RECOVERED 2026-09-14.** A verified full snapshot is stored at `D:\Cornerstone-Labs\recovery-snapshots\20260914-pre-vcs\keyboard-v2`. The working tree is now branch `recovery/local-20260914` on top of remote `main` commit `cc09a378a61e20b2c95ef61d59c183edef7dc789`; the seven orphaned submodule directories are vendored, all dead pointer files are retained as ignored `.git.dangling` evidence, and nothing has been pushed.

## Current damage

- `.git` is a text file: `gitdir: C:/Cornerstone/Temp/cornerstone-keyboard-audit/.git/worktrees/keyboard-v2` - that Temp tree no longer exists, so every `git` command fails with `not a repository: (NULL)`.
- Six submodule dirs contain the same kind of dangling `.git` pointer file into the missing worktree: `libs`, `translations`, `java/assets/layouts`, `java/assets/themes`, `java/res-large`, `java/assets/futo-swipe`. (`voiceinput-shared/src/main/ml` has no `.git` at all.)
- `.gitmodules` still declares all 7 submodules with their upstream URLs.
- The original remote is `github.com/KushBeaverTTV/cornerstone-keyboard`.
- Consequence: no baseline commit, no diff, no recorded submodule pins. The
  working tree on disk is the only source of truth - protect it first.

## Step 0 - snapshot before anything else

Take a full byte-level copy OUTSIDE the repo, including the dangling `.git`
pointer files and `releases/` (the only record of shipped versions). ~7+ GB
because of staged APKs; use a fast local disk.

```powershell
# run from a normal shell, NOT inside the repo being copied
robocopy D:\Cornerstone-Labs\keyboard-v2 D:\Cornerstone-Labs\keyboard-v2-SNAPSHOT-20260913 /E /COPY:DAT /DCOPY:DAT /R:1 /W:1
```

Verify the snapshot by comparing file counts:

```powershell
(Get-ChildItem D:\Cornerstone-Labs\keyboard-v2 -Recurse -File -Force | Measure-Object).Count
(Get-ChildItem D:\Cornerstone-Labs\keyboard-v2-SNAPSHOT-20260913 -Recurse -File -Force | Measure-Object).Count
```

Do not proceed until the counts match.

## Step 1 - neutralize the dangling .git pointer files

Never delete them outright - rename so the state is recoverable:

```powershell
cd D:\Cornerstone-Labs\keyboard-v2
foreach ($p in '.git','libs\.git','translations\.git','java\assets\layouts\.git','java\assets\themes\.git','java\res-large\.git','java\assets\futo-swipe\.git') {
    Rename-Item -LiteralPath $p -NewName '.git.dangling' -ErrorAction Stop
}
```

## Step 2 - pick ONE of the repair options

### Option A - `git init` in place, vendor everything (RECOMMENDED)

```powershell
cd D:\Cornerstone-Labs\keyboard-v2
git init -b master
git add -A
git status    # sanity: local.properties, keystore.properties, releases/, libs-fork/ must NOT appear (all gitignored)
git commit -m "Snapshot: working tree recovered from dead worktree (no prior history available)"
```

- What you get: a clean, self-contained repo that exactly matches the tree that
  produces current builds. `libs/`, `translations/` etc. become normal vendored
  directories; `.gitmodules` stays inert.
- What you lose: all upstream FUTO history, all fork history (none was pushed
  locally anyway), and the submodule linkage - future upstream syncs become
  manual diffs instead of `git submodule update`.
- Optional: then `git remote add origin https://github.com/KushBeaverTTV/cornerstone-keyboard.git`
  and `git fetch origin` to keep the remote as a read-only reference for manual
  diffing. Do NOT `git reset --hard origin/...` - that would destroy the
  fork-only work that exists only in this tree.

### Option B - clone the remote, overlay this tree, commit the delta

```powershell
git clone https://github.com/KushBeaverTTV/cornerstone-keyboard.git D:\Cornerstone-Labs\keyboard-v3-fresh
robocopy D:\Cornerstone-Labs\keyboard-v2 D:\Cornerstone-Labs\keyboard-v3-fresh /E /XD .git /XF .git
cd D:\Cornerstone-Labs\keyboard-v3-fresh
git add -A
git commit -m "Local fork state recovered from worktree loss"
```

- What you get: preserved remote history + a single commit showing every local
  divergence as a real diff - the best forensic view of the fork-only work.
- Risk: remote `HEAD` may be newer/older than the point this tree forked from,
  so the "diff" can include phantom upstream changes you never made. Review
  `git diff` before committing; if the remote repo is empty or stale, Option A
  is strictly better.
- Never run `git push --force` over the remote until you have confirmed the
  remote contains nothing the local tree lacks.

### Option C - restore true submodules

Only worth it if upstream sync matters. After Option A or B, for each of the 7
paths in `.gitmodules`: move the vendored dir aside, `git clone <url> <path>`,
then diff the fresh clone against the set-aside copy to find the matching
upstream commit (`git checkout <commit>` inside the submodule), then
`git add <path>` in the superproject to record the gitlink. Labor-intensive;
the pinned commits are unrecoverable, so this is approximation, not restoration.
Skip unless upstream tracking is actually needed - the fork already diverges
(e.g. `libs-fork/` exists precisely because `libs/` was left upstream-pure).

## Step 3 - verify afterwards

- `git status` clean (empty output) right after the snapshot commit.
- `git fsck --full` - no errors.
- `git ls-files | Measure-Object -Line` vs the snapshot file count minus
  gitignored paths (`build/`, `.gradle/`, `.cxx/`, `releases/`, `libs-fork/`,
  `local.properties`, `keystore.properties`, `*.dangling` files - add
  `.git.dangling` to `.gitignore` or delete after confidence).
- Confirm no secrets were committed: `git ls-files | Select-String -Pattern 'local.properties|keystore|signing.env|\.env$'` must return nothing.
- `.\gradlew.bat --version` and, when a build window is acceptable, a full
  `assembleUnstableDebug` to prove the tree still builds.

## Recommendation

**Option A** (init in place, vendor everything, remote fetch-only). The fork's
value is the working tree, not its lost history; Option A preserves it exactly
with zero risk of remote interaction destroying fork-only files. Option B is
the better *forensic* choice if the remote is confirmed to hold real history,
but it costs a second ~7 GB clone and a careful diff review.

## Trade-offs acknowledged

- Vendored submodules bloat the repo (~hundreds of MB in `java/res-large`,
  `voiceinput-shared`, `libs`) and sever upstream sync.
- Any future `git push` to `KushBeaverTTV/cornerstone-keyboard` publishes a new
  root history; coordinate before pushing anywhere.
- The renamed `.git.dangling` files are dead weight - safe to delete once the
  new repo is verified, keeping them is harmless either way.
