# Changelog

All notable changes to this project are documented in this file.

## 2.3.0 - 2026-03-23

- Added a compatibility-focused release target for Minecraft 1.16.5 through 1.21.x.
- Hardened tracked mine block handling and scoped managed break logic to valid mine blocks only.
- Preserved automatic mine respawn timing across reload and restart flows.
- Improved SQLite reliability with safer transaction handling, indexes, WAL mode and resource cleanup.
- Prevented cross-mine data deletion when removing block types.
- Added repository-ready packaging with current release artifact.
