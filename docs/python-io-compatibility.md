# Python in-memory streams

The shared Python source host now supplies device-local `io.StringIO` and
`io.BytesIO`. This addresses sources that build JSON text, inspect downloaded
binary data or retain an in-memory buffer between catalogue, detail and player
calls. Android and Flutter load the same implementation.

Supported operations include `read`, `readline`, `readlines`, iteration,
`write`, `writelines`, `seek`, `tell`, `truncate`, `getvalue`, closing and context
managers. Binary streams also support `read1`. Text positions count Unicode
characters; binary positions count bytes. Writes overwrite at the current
position and fill intervening space with zeroes when needed. Closing discards
the buffer and rejects subsequent I/O. The `closed` property is read-only.

`UnsupportedOperation` is catchable as both `OSError` and `ValueError`. The host
supplies the omitted `OSError` name using its existing exception storage; this
does not implement the operating-system module or filesystem APIs.

## Explicit boundaries

- Each stream is limited to 2 MiB of bytes or text characters, within the source
  VM's existing memory and execution limits. A rejected write preserves contents
  and position. Seeking does not allocate memory.
- `StringIO` accepts the default newline mode (`'\n'`). Universal-newline and
  translated-newline modes are explicitly unsupported.
- `BytesIO` accepts immutable Python `bytes`. Mutable exported views,
  `getbuffer`, `readinto`, general buffer-protocol objects and native extensions
  are not supplied.
- These buffers are not files. This change adds no `open`, persistent cookie
  storage, `os`, subprocesses, Pillow or other native modules.

## Verification

`scripts/fixtures/python_io_contract.py` is an independently authored oracle
fixture run under CPython 3.13.13. The 29 result groups are compared in the
no-JIT Node host and the actual Flutter QuickJS host. Additional checks cover
allocation bounds and unsupported operations. A Flutter client test traverses
catalogue, detail and a separate player client, preserving stream position and
sending exact binary request bytes to a loopback server.

The iOS simulator fixture and Android API 23 fixture include the same production
assets. Their latest run results must be checked separately; passing local tests
or building an IPA does not establish a physical iPhone's playback behavior.
