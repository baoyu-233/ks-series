# ksHWP World Map - Player Guide

> [中文](GUIDE.md) | English

Run `/map` in game to receive a clickable authenticated map link. Direct access to
`http://<server-ip>:8123/kSHWP` can view public content but cannot use authenticated note and refresh actions.

| Action | Input |
|---|---|
| Pan | Drag with the left mouse button |
| Zoom | Mouse wheel or +/- controls |
| Select a point | Right-click |
| Select an area | Shift + left-drag |
| Switch world | Select a world in the sidebar |

The sidebar can switch between the Overworld, Nether, and End. Previously rendered tiles remain on disk. Green shows
grass, blue water, gray stone/buildings, and red `unexplored` areas. Any player exploration can contribute to the
shared map.

Use a point or area selection to add a note, choose a category, and search notes from the sidebar. Your private notes
remain private. Use `/map hidden` when the server grants `kshwp.hidden` and you do not want your position shown.

An authenticated `/map` link is required to refresh an area after building. The map data survives server restarts.
