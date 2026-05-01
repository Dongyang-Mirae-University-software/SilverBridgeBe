# MCP Configuration Instructions

This file explains how to configure Model Context Protocol (MCP) servers
for your project. It is generated from a template and tailored for each project.

---

## 1. Local Filesystem MCP

- **Purpose**: Allows Claude Code to navigate the project file tree efficiently
- **Root directory**: /home/skarndaudwls/SilverBridgeBe
- **Server command**:
```bash
npx @modelcontextprotocol/server-filesystem --path "/home/skarndaudwls/SilverBridgeBe"
```

## Best Practices
1. Load skills once per session - avoids unnecessary token usage
2. Batch operations - process multiple files/issues together
3. Use MCP whenever possible - more efficient than shell commands
4. Keep this file updated - helps onboarding new contributors

**Notes**:

- For more info on MCP, see:
  - [Model Context Protocol Docs](https://modelcontextprotocol.io/docs/getting-started/intro)
