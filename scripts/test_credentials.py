import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import credentials


EXAMPLE = {"protocol": "ftp", "host": "192.168.1.2", "port": 2121,
           "username": "example", "password": "example-only-not-real", "directory": "/"}


class CredentialsTests(unittest.TestCase):
    def test_blank_entries_preserve_current_credentials_without_reentering_password(self):
        with patch("builtins.input", side_effect=[""] * 5), \
                patch.object(credentials.getpass, "getpass", return_value="") as password:
            self.assertEqual(credentials.prompt_connection(EXAMPLE), EXAMPLE)
            password.assert_called_once()

    def test_mismatched_password_confirmation_is_rejected(self):
        with patch("builtins.input", side_effect=[""] * 5), \
                patch.object(credentials.getpass, "getpass", side_effect=["new-example", "different-example"]):
            with self.assertRaises(ValueError):
                credentials.prompt_connection(EXAMPLE)

    def test_refuses_password_entry_if_the_terminal_would_echo_it(self):
        with patch("builtins.input", side_effect=[""] * 5), \
                patch.object(credentials.getpass, "getpass", side_effect=credentials.getpass.GetPassWarning()):
            with self.assertRaises(ValueError):
                credentials.prompt_connection(EXAMPLE)

    def test_atomic_save_is_private_and_invalid_input_preserves_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "hosting.local.json"
            credentials.save_connection(file, EXAMPLE)
            self.assertEqual(json.loads(file.read_text()), EXAMPLE)
            if os.name != "nt":
                self.assertEqual(file.stat().st_mode & 0o777, 0o600)
            before = file.read_bytes()
            with self.assertRaises(ValueError):
                credentials.save_connection(file, {**EXAMPLE, "port": 0})
            self.assertEqual(file.read_bytes(), before)
            self.assertEqual(list(Path(directory).iterdir()), [file])


if __name__ == "__main__":
    unittest.main()
