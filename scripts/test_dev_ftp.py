import json
import os
from pathlib import Path
import tempfile
import unittest

import dev_ftp


class DevelopmentFTPTests(unittest.TestCase):
    def test_creates_private_credentials_once_and_reuses_them(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "hosting.local.json"
            first = dev_ftp.configure(file, "192.168.1.2", 2121)
            self.assertGreaterEqual(len(first["password"]), 24)
            self.assertEqual(dev_ftp.configure(file, "192.168.1.2", 2121), first)
            if os.name != "nt":
                self.assertEqual(file.stat().st_mode & 0o777, 0o600)

    def test_never_overwrites_a_different_host_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "hosting.local.json"
            original = {"protocol": "sftp", "host": "other.example.test", "port": 22,
                        "username": "example", "password": "example-only", "directory": "/public_html"}
            file.write_text(json.dumps(original))
            with self.assertRaises(ValueError):
                dev_ftp.configure(file, "192.168.1.2", 2121)
            self.assertEqual(json.loads(file.read_text()), original)


if __name__ == "__main__":
    unittest.main()
