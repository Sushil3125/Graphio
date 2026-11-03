# Saved Claude Code session

`679583d0-1cbe-4a24-b9b3-6a164c92c301.jsonl` is the conversation that built this project (snapshot; the Marketplace token is redacted).
It includes code excerpts from the private gis_api project, so keep this repo private.

## Resume on another device
1. Clone this repo and `cd` into it.
2. Copy the session into Claude Code's project folder for that directory (absolute path with `/` and `.` replaced by `-`):
   ```bash
   P=$(pwd | sed 's#[/.]#-#g')
   CFG=${CLAUDE_CONFIG_DIR:-$HOME/.claude}      # ~/.claude-gis if you use the claude-gis alias
   mkdir -p "$CFG/projects/$P" && cp sessions/679583d0-1cbe-4a24-b9b3-6a164c92c301.jsonl "$CFG/projects/$P/"
   ```
3. `claude --resume 679583d0-1cbe-4a24-b9b3-6a164c92c301`

Paths in the history (`/home/sushil/...`) refer to the original machine; tell Claude where the project lives on the new device. TODO.md has the remaining work.
