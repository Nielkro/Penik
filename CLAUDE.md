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

## 3. Critical cost constraints & tool efficiency
- **Fixed Cost:** Every single API request to the LLM costs a fixed amount ($0.20 - $0.30).
- You MUST minimize the number of tool calls and total agent turns. 
- DO NOT use atomic, sequential commands (e.g., calling `cat file1`, waiting for output, then calling `cat file2`).
- DO NOT make informational tool calls just to "look around" if you can combine them.

## 4. Batching and tool combination
- **File Reading:** If you need to inspect multiple files, read them all in a single command using a loop or multi-file arguments: `cat file1 file2 file3`.
- **Searching/Grepping:** Combine search and viewing into one step. Use piped bash chains:
  * *Bad (3 requests):* List files -> grep text -> read file
  * *Good (1 request):* `find . -name "*.go" -exec grep -H "target" {} +`
- **Multi-File Modifications (CRITICAL):** NEVER edit files one by one across multiple agent turns if they are part of the same feature or refactoring task. Prepare edits for ALL target files (e.g., `parser.go`, `analyze.go`, `emit.go`, `type.go`) in memory first, and apply them simultaneously in a single pass or within a single command execution block.
- **Testing:** Run compilation, linting, and tests together using subshells or `&&` chains: `go build ./... && go test ./...`. Do not run them as separate agent interactions.

## 5. Interaction rules
- Be decisive. Do not ask for confirmation between intermediate analytical steps.
- If you lack context, fetch ALL potentially relevant files at once in your very first tool call.
- Provide the final refactored code and solution in the fewest turns possible. Aim for a 1-2 turn completion per task.

## 6. Auto-Commit Rule
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
