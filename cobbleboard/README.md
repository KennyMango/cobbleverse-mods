# CobbleBoard v0.4.0

Adds `status` display mode for non-leaderboard information panels.

For CobbleBounty:
```mcfunction
/cobbleboard board limit dailybounty 3
/cobbleboard board mode dailybounty status
/cobbleboard board title dailybounty TODAY'S BOUNTY
/cobbleboard board refresh dailybounty
```

Status mode reads the current scoreboard entries directly, orders them by score, and shows only their names. No rank numbers and no scoreboard values are rendered.
