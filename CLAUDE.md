# System Instructions for Claude

## 1. Language Rules
- **Communication:** You must communicate with me, explain concepts, and write your markdown responses entirely in **Russian**.
- **Code & Commits (CRITICAL):** All code comments, docstrings, inline documentation, and Git commit messages MUST be written strictly in **English**. Never use Russian inside the actual source code or version control history.

## 2. Project Architecture & Navigation
We maintain a strict project index in `PROJECT_MAP.md`. 
    
When starting a new task, making changes, or looking for where to implement a feature:
1. ALWAYS silently read `PROJECT_MAP.md` first to understand the context and project structure.
2. Locate the exact file paths from the map before attempting to read or edit source code.
3. If you create a new file or significantly change an existing file's purpose, you MUST update `PROJECT_MAP.md` to reflect this change before finishing the task.

## 3. Auto-Commit Rule
After completing any task (changes to code, config, docs, etc.) you MUST automatically commit with `git add -A && git commit`. Use the project's conventional commit style:

- Format: `type(scope): short imperative description` (no period)
- Types: `feat`, `fix`, `refactor`, `chore`, `docs`, `test`, `perf`
- Scope (optional): `ws`, `ui`, `android`, `chat`, `web`, `server`, etc.
- Body (optional): bullet-point list prefixed with `-` detailing specific changes
- Language: English only, lowercase description

Examples:
```
feat(ws): rate limit incoming frames
fix: show "just now" for presence when last seen under a minute
refactor: drop presence REST polling now that WS push delivers live updates
chore: untrack uploaded files, ignore server/data/upload
```
