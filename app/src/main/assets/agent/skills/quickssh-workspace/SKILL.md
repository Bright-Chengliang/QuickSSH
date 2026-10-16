---
name: quickssh-workspace
description: View and change the QuickSSH app's own configuration - SSH servers, workspaces, folders, pinned items and port-forward tunnel presets - through the `quickssh` CLI and the QuickSSH Agent Bridge. Use when the user asks to add, rename, move, organize, bulk-edit, clean up, back up or audit QuickSSH hosts/workspaces/folders/tunnels (服务器/主机/工作区/文件夹/置顶/隧道/端口转发 配置).
compatibility: Runs inside the QuickSSH local terminal, inside Termux on the same phone, or over SSH (QuickSSH built-in SSH server or Termux sshd). The same operations are exposed as MCP tools (quickssh_*). Needs the Agent Bridge enabled in QuickSSH Settings.
---

# QuickSSH workspace configuration

QuickSSH stores its configuration in a private database. You change it through the `quickssh`
CLI, which calls the QuickSSH Agent Bridge (HTTP on `127.0.0.1`, default port 8024, bearer token).
The QuickSSH UI updates live after every successful change. Never try to edit the database file.

If you have the `quickssh_*` MCP tools (server `quickssh`), use them instead of the CLI: they map
1:1 onto the commands below (`quickssh_tree`, `quickssh_export`, `quickssh_apply`, `quickssh_call`,
`quickssh_devices`, `quickssh_guide`) and follow the same rules. See "MCP" at the end.

## Before you start

```sh
quickssh status     # {"app":"QuickSSH","status":"running",...}  -> bridge is up
quickssh env        # shows the env file, bridge URL and whether a token is set
```

- `command not found` / connection refused: ask the user to open QuickSSH > Settings, enable
  "Termux & Agent gateway" (Termux & Agent 互通网关). In Termux, they then run the copied setup
  command once; inside the QuickSSH local terminal nothing else is needed (reopen the terminal).
- `401 Unauthorized`: the token was regenerated; the user must rerun the setup command
  (Termux) or reopen the local terminal (QuickSSH).
- The bridge runs while the QuickSSH app is open (it may be in the background).
- `Permission denied` for `~/bin/quickssh` inside the QuickSSH app: Android 10+ forbids executing
  files in app storage. Login shells get a `quickssh` shell function; elsewhere run
  `sh ~/bin/quickssh ...`.

## Data model

```
folders (optional grouping, plus a special "pinned" group)
servers   = one SSH endpoint + credentials: name, host, port, username, authType
  workspaces = what the user actually opens: name, workDirectory, postConnectCommand,
               terminal settings, persistentSessionMode, folder/pinned, preConnectTunnel
    tunnels  = local port-forward presets: name, remoteHost, remotePort, localPort (0 = auto)
```

- A workspace belongs to exactly one server; a server always has at least one workspace
  (deleting its last workspace deletes the server).
- Several workspaces share one server's credentials. Changing a server's host or password
  affects all its workspaces.
- A workspace is in at most one folder, or `pinned`, or neither. Folders hold workspaces from
  any server.
- `preConnectTunnel` is a tunnel QuickSSH opens before the SSH session and before every file
  transfer (jump/bastion style). It is usually a tunnel of *another* workspace (the jump host),
  and the workspace's host/port then points at that tunnel's local end (`127.0.0.1:<localPort>`).
  Write it as the workspace's own tunnel name, `server/workspace/tunnel`, a name that is unique
  across all workspaces, or `preConnectTunnelId`. Export always uses the bare name or the
  `server/workspace/tunnel` form. In the app, a folder or server card can set one tunnel for all of
  its workspaces at once.
- `authType`: `PASSWORD`, `PRIVATE_KEY` or `LOCAL` (a local shell on the phone; host is the
  shell, e.g. `builtin`, port 0).
- `persistentSessionMode`: `none`, `tmux`, `screen` or `auto` (Linux/macOS hosts).
- `terminalFontSizeSp` is clamped to 10-20. `terminalTerm` defaults to `xterm-256color`.

## Reading

```sh
quickssh tree      # everything with ids - use this before granular changes
quickssh export    # declarative document without ids - use this for bulk edits
```

Secrets are write-only: reads show `hasPassword` / `hasPrivateKey` / `credentialStored`, never
the values. Do not ask the user to paste passwords into chat unless they offer; prefer telling
them to enter credentials in the app.

## Small changes: `quickssh call <resource>/<action> '<json>'`

Update semantics everywhere: an absent key keeps the stored value, an explicit `null` clears
it. Unknown keys are rejected (so typos fail loudly). Pass JSON inline, as a file path, or `-`
for stdin. Prefer single-quoted JSON in the shell; for text with quotes use a heredoc + `-`.

| action | required | other keys |
|---|---|---|
| `servers/create` | `host`, `username` (not for LOCAL) | `name`, `port`, `authType`, `password`, `privateKey`, `workspaces` (array of workspace objects; default one is created if absent) |
| `servers/update` | `id` | same as create (no `workspaces`) |
| `servers/delete` | `id` | deletes all its workspaces and tunnels |
| `workspaces/create` | `name` and `serverId` **or** inline `server` object (reused if host/port/username/authType match) | `workDirectory`, `postConnectCommand`, `terminalFontSizeSp`, `terminalWrapEnabled`, `terminalTerm`, `terminalShortcuts`, `persistentSessionMode`, `folder` (name) or `folderId`, `pinned` |
| `workspaces/update` | `id` | all of the above, `serverId` (move to another server), `preConnectTunnel` (name) or `preConnectTunnelId` |
| `workspaces/delete` | `id` | removes the server too if it was the last workspace |
| `folders/create` | `name` | - |
| `folders/update` | `id` | `name` (rename) |
| `folders/delete` | `id` | its workspaces become unfiled |
| `tunnels/create` | `workspaceId`, `remotePort` | `name`, `note`, `remoteHost` (default 127.0.0.1), `localPort` (0 = auto) |
| `tunnels/update` | `id` | `name`, `note`, `remoteHost`, `remotePort`, `localPort` |
| `tunnels/delete` | `id` | clears it as pre-connect tunnel |

Examples:

```sh
quickssh call folders/create '{"name":"GPU"}'
quickssh call workspaces/update '{"id":12,"folder":"GPU","persistentSessionMode":"tmux"}'
quickssh call workspaces/update '{"id":12,"pinned":true}'
quickssh call workspaces/create '{"serverId":3,"name":"logs","workDirectory":"/var/log"}'
quickssh call workspaces/create '{"name":"train","server":{"host":"10.0.0.8","username":"ubuntu","authType":"PRIVATE_KEY"}}'
quickssh call tunnels/create '{"workspaceId":12,"name":"jupyter","remotePort":8888,"localPort":18888}'
quickssh call workspaces/update '{"id":12,"preConnectTunnel":"jupyter"}'
quickssh call servers/update '{"id":3,"name":"gpu-a100","host":"10.0.0.9"}'
```

## Bulk changes: export -> edit -> dry run -> apply

```sh
quickssh export ws.json            # edit ws.json
quickssh apply ws.json --dry-run   # shows {"changes":[...],"warnings":[...]} - nothing is written
quickssh apply ws.json             # applies atomically (all or nothing)
```

Document format (`format: quickssh-workspace/v1`):

```json
{
  "format": "quickssh-workspace/v1",
  "folders": ["GPU", "Web"],
  "servers": [
    {
      "name": "gpu-a100", "host": "10.0.0.9", "port": 22, "username": "ubuntu", "authType": "PRIVATE_KEY",
      "workspaces": [
        {
          "name": "train", "workDirectory": "/data/train", "folder": "GPU", "pinned": false,
          "persistentSessionMode": "tmux", "preConnectTunnel": null,
          "tunnels": [{"name": "tensorboard", "remoteHost": "127.0.0.1", "remotePort": 6006, "localPort": 16006}]
        }
      ]
    }
  ]
}
```

Matching rules: servers by `name`, otherwise by host+port+username+authType (so renaming a
server keeps it); workspaces by `name` within their server; tunnels by `name` within their
workspace; folders by name. Anything not matched is created. Folders referenced by a workspace
are created automatically. Absent keys keep stored values; secrets are kept unless you add
`password` / `privateKey`.

A partial document is fine: list only the servers/workspaces you want to change.

`--prune` makes the document authoritative: unlisted servers are deleted, unlisted workspaces
of listed servers are deleted, unlisted tunnels of workspaces that have a `tunnels` array are
deleted, unlisted folders are deleted when `folders` is present, and list order becomes
display order. **Only use `--prune` on a full export you edited, always dry-run it first, and
show the user the planned deletions before applying.**

## Working rules

1. Run `quickssh tree` (or `export`) first; never guess ids.
2. For anything that deletes or touches many objects, run a dry run (`apply --dry-run`) or
   list exactly what you will change, and get the user's confirmation.
3. Check every response: `{"ok":false,"error":...}` means nothing was changed; fix the request
   using the error text.
4. After changes, summarize what changed (names, not just ids).
5. Backups: `quickssh export backup.json` saves structure without secrets. Full backups with
   credentials are done in the app (Settings > export, optionally password-protected).

## Using it from another machine

QuickSSH's built-in SSH server (Settings > Built-in SSH Server / 内置 SSH 服务端, default port
8025, any user name, key from `~/.ssh/authorized_keys` or the generated password) logs into this
same environment, so every command works remotely:

```sh
ssh -p 8025 quickssh@<phone-ip> quickssh tree
ssh -p 8025 quickssh@<phone-ip> quickssh export > ws.json
ssh -p 8025 quickssh@<phone-ip> quickssh apply - --dry-run < ws.json
ssh -p 8025 quickssh@<phone-ip> quickssh skill   # this document
```

Termux sshd (started by the Termux setup command, default port 8023) works the same way with the
Termux user. To use HTTP or MCP from the other machine, forward the bridge (only loopback targets
are allowed) and read the token on the phone:

```sh
ssh -N -L 8024:127.0.0.1:8024 -p 8025 quickssh@<phone-ip> &
TOKEN=$(ssh -p 8025 quickssh@<phone-ip> cat .config/quickssh/bridge.token)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8024/api/v2/tree
```

HTTP API: `GET /api/v2/tree`, `GET /api/v2/export`, `GET /api/v2/skill`,
`POST /api/v2/apply?dryRun=1&prune=1` (body = document), `POST /api/v2/<resource>/<action>`
(body = JSON). `GET /api/status` needs no token.

## MCP

The bridge is also an MCP server (Streamable HTTP, JSON responses) at
`http://127.0.0.1:8024/mcp`, authenticated with the same bearer token. Tools:

| tool | same as |
|---|---|
| `quickssh_tree` | `quickssh tree` |
| `quickssh_export` | `quickssh export` |
| `quickssh_apply {document, dryRun, prune?}` | `quickssh apply` (`dryRun` is required: `true` plans, `false` writes) |
| `quickssh_call {action, params}` | `quickssh call <action> '<params>'` |
| `quickssh_devices` | `quickssh devices` |
| `quickssh_guide` | `quickssh skill` |

opencode is registered automatically: QuickSSH writes `~/.config/opencode/opencode.json` (server
`quickssh`, token read from `~/.config/quickssh/bridge.token`) unless you already have your own
config there (the Termux setup then leaves the entry in `opencode.json.quickssh` to merge). Other clients, e.g.
`claude mcp add --transport http quickssh http://127.0.0.1:8024/mcp --header "Authorization: Bearer <token>"`.
