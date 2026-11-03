"""CLI: python -m codemap [root] [--port 8765] [--host 127.0.0.1] [--exclude GLOB ...] [--no-watch]
                         [--exit-on-stdin-eof] [--ide-port N]

stdout carries exactly one machine-readable line once the socket is bound:
    CODEMAP_READY port=<n> url=http://127.0.0.1:<n>/
everything else (logs, errors) goes to stderr. ``--port 0`` picks a free port.
"""
from __future__ import annotations

import argparse
import errno
import os
import sys


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="python -m codemap",
        description="Live left-to-right call-flow explorer for a Python project.",
    )
    p.add_argument("root", nargs="?", default=None,
                   help="optional default project served at / (other projects: /open?root=<abs path>)")
    p.add_argument("--port", type=int, default=int(os.environ.get("CODEMAP_PORT", 8765)),
                   help="TCP port (0 = pick a free one; see the CODEMAP_READY line on stdout)")
    p.add_argument("--host", default=os.environ.get("CODEMAP_HOST", "127.0.0.1"))
    p.add_argument("--exclude", action="append", default=[], metavar="GLOB",
                   help="extra glob to exclude (repeatable)")
    p.add_argument("--no-watch", action="store_true", help="do not watch files for changes")
    p.add_argument("--exit-on-stdin-eof", action="store_true",
                   help="shut down when stdin is closed (used by the IDE plugin to avoid orphans)")
    p.add_argument("--ide-port", type=int, default=None, metavar="N",
                   help="built-in server port of the owning IDE; tried first when opening files in the IDE")
    args = p.parse_args(argv)

    root = os.path.abspath(os.path.expanduser(args.root)) if args.root else None
    if root and not os.path.isdir(root):
        print(f"codemap: error: not a directory: {root}", file=sys.stderr)
        return 2
    excludes = list(args.exclude)

    from codemap.server import serve

    try:
        serve(root, host=args.host, port=args.port, excludes=excludes, watch=not args.no_watch,
              ide_port=args.ide_port, exit_on_stdin_eof=args.exit_on_stdin_eof)
    except OSError as exc:
        if exc.errno in (errno.EADDRINUSE, getattr(errno, "WSAEADDRINUSE", -1)) or getattr(exc, "winerror", None) == 10048:
            print(
                f"codemap: error: port {args.port} on {args.host} is already in use.\n"
                f"  Is codemap already running? Check http://{args.host}:{args.port}/api/status\n"
                f"  or start on another port: python -m codemap {args.root or ''} --port {args.port + 1}"
                f"  (or --port 0 for any free port)",
                file=sys.stderr,
            )
            return 1
        raise
    return 0


if __name__ == "__main__":
    sys.exit(main())
