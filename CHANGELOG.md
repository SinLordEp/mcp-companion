<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MCP Companion Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- `mc_status` MCP tool: plugin version, IDE build, target project and the state of every MCP Companion toolset
- Toolsets step aside by themselves when an official MCP tool provides the same capability
- Settings | Tools | MCP Companion: read-only overview of the toolsets and their state, with a link to the bundled
  MCP Server's tool page, where tools are switched on and off (English and Simplified Chinese)
- `mc_find_usages`: every reference to a symbol in any language, with scope, read/write filter and paging
- `mc_find_implementations`: implementations, inheritors and overriding methods, or the super declarations
- `mc_type_hierarchy`: supertypes and subtypes of a JVM class as a graph, with depth and node limits
- `mc_safe_delete`: preview what blocks deleting a declaration; apply deletes only without blockers, undoably and
  without any dialog; unused overriding methods go with a deleted method
- Locations in results match the bundled MCP Server's path form (usable with `read_file`) and carry their origin
- `mc_run`: start, stop, restart and inspect existing run configurations (run or debug) by process handle, read their
  captured console output (tail, regex filter, cursor), wait until they are ready (output pattern, failure pattern,
  port, end of process) and hot-swap changed classes into JVM debug sessions; also sees processes started in the IDE;
  never opens a dialog
