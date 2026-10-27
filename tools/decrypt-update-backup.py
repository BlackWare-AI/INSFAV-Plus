#!/usr/bin/env python3
"""Authenticate an INSFAV .ifbackup and write its ZIP; never extracts files automatically."""
import argparse
import getpass
import os
from pathlib import Path
import struct
import tempfile
import zipfile
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

MAGIC = b"INSFAV-BACKUP-2\n"
CHUNK = 256 * 1024

def decrypt(source, target, application, pin):
    if len(pin) != 4 or any(c not in "0123456789" for c in pin):
        raise ValueError("PIN must contain exactly four ASCII digits")
    target = Path(target)
    if target.exists():
        raise ValueError("Output already exists")
    temp = None
    try:
        with open(source, "rb") as archive:
            if archive.read(len(MAGIC)) != MAGIC:
                raise ValueError("Unsupported backup")
            salt, prefix = archive.read(16), archive.read(8)
            if len(salt) != 16 or len(prefix) != 8:
                raise ValueError("Incomplete backup")
            key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=210000).derive(pin.encode("ascii"))
            cipher = AESGCM(key)
            with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".insfav-", delete=False) as output:
                temp = Path(output.name)
                sequence = 0
                while True:
                    raw = archive.read(4)
                    if len(raw) != 4:
                        raise ValueError("Missing final frame")
                    length = struct.unpack(">I", raw)[0]
                    if length > CHUNK or sequence > 0x7fffffff:
                        raise ValueError("Invalid frame")
                    encoded = archive.read(length + 16)
                    if len(encoded) != length + 16:
                        raise ValueError("Incomplete frame")
                    counter = struct.pack(">I", sequence)
                    last = length == 0
                    plain = cipher.decrypt(prefix + counter, encoded, application.encode("utf-8") + counter + bytes([int(last)]))
                    output.write(plain)
                    sequence += 1
                    if last:
                        if archive.read(1):
                            raise ValueError("Trailing data")
                        break
                output.flush()
                os.fsync(output.fileno())
            with zipfile.ZipFile(temp) as zipped:
                if zipped.testzip() is not None:
                    raise ValueError("Invalid ZIP")
            # Exclusive creation never overwrites an existing recovery file.
            with open(target, "xb") as output, open(temp, "rb") as plain:
                try:
                    while chunk := plain.read(65536):
                        output.write(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                except BaseException:
                    target.unlink(missing_ok=True)
                    raise
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--application", choices=["com.instagram.insfav", "com.instagram.insfav.plus"], required=True)
    args = parser.parse_args()
    try:
        decrypt(args.source, args.target, args.application, getpass.getpass("4-digit backup PIN: "))
        print("Authenticated ZIP saved. Keep this file private: it contains account cookies.")
    except Exception:
        parser.exit(1, "Backup could not be authenticated or saved; no complete output was created.\n")
