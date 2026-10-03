"""FTP/FTPS fixture servers (pyftpdlib) for protocol tests.

2121  normal FTP: REST, passive, TYPE I, MLSD          (alice rw / bob read-only)
2122  FTP without REST support (REST answered 502)
2123  FTP that ends RETR data early (SIZE says full size, data stops at half, then 226)
2124  explicit FTPS (AUTH TLS, PROT P) with a self-signed certificate (/certs/ftps.pem)
"""
import os
import shutil
import multiprocessing

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.filesystems import AbstractedFS
from pyftpdlib.handlers import FTPHandler, TLS_FTPHandler
from pyftpdlib.servers import ThreadedFTPServer

ROOT = "/srv/ftp"


def prepare():
    if os.path.exists(ROOT):
        shutil.rmtree(ROOT)
    shutil.copytree("/fixture", ROOT)
    os.makedirs(os.path.join(ROOT, "rw"), exist_ok=True)
    locked = os.path.join(ROOT, "Locked")
    os.makedirs(locked, exist_ok=True)


def authorizer():
    a = DummyAuthorizer()
    a.add_user("alice", "alicepass", ROOT, perm="elradfmwMT")
    a.add_user("bob", "bobpass", ROOT, perm="elr")
    a.add_anonymous(os.path.join(ROOT, "fixture", "Album-B"), perm="elr")
    # "Locked" is unreadable for both users (permission-denied root for the connection test).
    for u in ("alice", "bob"):
        a.override_perm(u, os.path.join(ROOT, "Locked"), perm="", recursive=True)
    return a


class NoRestHandler(FTPHandler):
    def ftp_REST(self, line):
        self.respond("502 Command not implemented.")


class HalfFile:
    def __init__(self, f, limit):
        self._f = f
        self._left = limit
        self.name = f.name
        self.closed = False

    def read(self, n=-1):
        if self._left <= 0:
            return b""
        n = self._left if n is None or n < 0 else min(n, self._left)
        data = self._f.read(n)
        self._left -= len(data)
        return data

    def seek(self, *a):
        return self._f.seek(*a)

    def tell(self):
        return self._f.tell()

    def close(self):
        self.closed = True
        self._f.close()


class TruncatingFS(AbstractedFS):
    def open(self, filename, mode):
        f = super().open(filename, mode)
        if "r" in mode:
            size = os.path.getsize(filename)
            return HalfFile(f, size // 2)
        return f


class TruncatingHandler(FTPHandler):
    abstracted_fs = TruncatingFS


def serve(handler_cls, port, pasv_from, tls=False):
    try:
        _serve(handler_cls, port, pasv_from, tls)
    except BaseException:
        import traceback
        traceback.print_exc()
        raise


def _serve(handler_cls, port, pasv_from, tls=False):
    class H(handler_cls):
        pass
    H.authorizer = authorizer()
    H.passive_ports = range(pasv_from, pasv_from + 20)
    H.banner = f"fixture-{port}"
    H.use_sendfile = False
    if tls:
        H.certfile = "/certs/ftps.pem"
        H.tls_control_required = True
        H.tls_data_required = True
    s = ThreadedFTPServer(("0.0.0.0", port), H)
    s.max_cons = 64
    s.serve_forever()


if __name__ == "__main__":
    prepare()
    servers = [
        (FTPHandler, 2121, 30000, False),
        (NoRestHandler, 2122, 30020, False),
        (TruncatingHandler, 2123, 30040, False),
        (TLS_FTPHandler, 2124, 30060, True),
    ]
    # One process per server: pyftpdlib's IOLoop is a per-process singleton.
    procs = [multiprocessing.Process(target=serve, args=s, daemon=True) for s in servers]
    for p in procs:
        p.start()
    for p in procs:
        p.join()
