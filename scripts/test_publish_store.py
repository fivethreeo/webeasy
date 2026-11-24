import base64
import json
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import zipfile

import publish_store


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.apk = self.root / "app.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", "test fixture")
            archive.writestr("classes.dex", "test fixture")
        self.ipa = self.root / "app.ipa"
        self.info = {"CFBundleIdentifier": "no.webeasy.app",
                     "CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "1"}
        self.write_ipa()
        self.icon = self.root / "icon.png"
        self.icon.write_bytes(base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg=="))
        self.styles = self.root / "site.css"
        self.styles.write_text("body { color: black; }")

    def tearDown(self):
        self.temporary.cleanup()

    def write_ipa(self):
        with zipfile.ZipFile(self.ipa, "w") as archive:
            archive.writestr("Payload/Webeasy.app/Info.plist", plistlib.dumps(self.info))

    def prepare(self):
        return publish_store.prepare_store(self.apk, self.ipa, self.icon, self.styles,
                                           self.root / "store", "https://example.test/store", "0.1.0", 1)

    def test_store_source_matches_ipa_metadata_and_downloads(self):
        files = self.prepare()
        self.assertEqual(files[-1].name, "source.json")
        source = json.loads(files[-1].read_text())
        app = source["apps"][0]
        self.assertEqual(app["bundleIdentifier"], "no.webeasy.app")
        release = app["versions"][0]
        self.assertEqual(release["version"], "0.1.0")
        self.assertEqual(release["buildVersion"], "1")
        self.assertEqual(release["size"], self.ipa.stat().st_size)
        self.assertTrue((self.root / "store" / release["downloadURL"].split("/")[-1]).is_file())
        self.assertIn("SideStore", (self.root / "store/index.html").read_text())
        self.assertEqual((self.root / "store/Webeasy-android-0.1.0-1.apk").read_bytes(), self.apk.read_bytes())

    def test_rejects_wrong_app_and_unsafe_destinations(self):
        self.info["CFBundleIdentifier"] = "no.other.app"
        self.write_ipa()
        with self.assertRaises(ValueError):
            self.prepare()
        for url in ["http://example.test/store", "https://user:secret@example.test/store",
                    "https://example.test/store?password=example"]:
            with self.assertRaises(ValueError):
                publish_store.public_base(url)
        with self.assertRaises(ValueError):
            publish_store.remote_directory("/public_html/../private")
        self.assertEqual(publish_store.public_base("http://192.168.1.2:4173/host/store", True),
                         "http://192.168.1.2:4173/host/store")
        with self.assertRaises(ValueError):
            publish_store.public_base("http://8.8.8.8/store", True)

    def test_upload_source_last_and_close_on_failure(self):
        files = self.prepare()
        connection = {"protocol": "ftps"}
        with patch.object(publish_store, "FTPPublisher") as publisher:
            publish_store.publish(files, connection, "/public_html/store")
            self.assertEqual(publisher.return_value.upload.call_args_list[-1].args[0].name, "source.json")
            publisher.return_value.close.assert_called_once()
        with patch.object(publish_store, "FTPPublisher") as publisher:
            publisher.return_value.upload.side_effect = OSError("test failure")
            with self.assertRaises(OSError):
                publish_store.publish(files, connection, "/public_html/store")
            publisher.return_value.close.assert_called_once()
        with self.assertRaises(ValueError):
            publish_store.publish(files, {"protocol": "ftp"}, "/public_html/store")

    def test_ftps_encrypts_control_and_data_and_renames_after_upload(self):
        files = self.prepare()
        connection = {"protocol": "ftps", "host": "example.test", "port": 21,
                      "username": "example", "password": "example-only"}
        with patch.object(publish_store.ftplib, "FTP_TLS") as ftp:
            publisher = publish_store.FTPPublisher(connection)
            ftp.return_value.prot_p.assert_called_once()
            publisher.upload(files[0], "/store")
            calls = [call[0] for call in ftp.return_value.method_calls]
            self.assertLess(calls.index("storbinary"), calls.index("rename"))
            self.assertEqual(ftp.return_value.rename.call_args.args[1], f"/store/{files[0].name}")
            publisher.close()

    def test_sftp_requires_trusted_host_keys_and_uses_atomic_rename(self):
        files = self.prepare()
        connection = {"protocol": "sftp", "host": "example.test", "port": 22,
                      "username": "example", "password": "example-only"}
        paramiko = MagicMock()
        with patch.dict("sys.modules", {"paramiko": paramiko}):
            publisher = publish_store.SFTPPublisher(connection)
            client = paramiko.SSHClient.return_value
            client.load_system_host_keys.assert_called_once()
            client.set_missing_host_key_policy.assert_called_once_with(paramiko.RejectPolicy.return_value)
            self.assertFalse(client.connect.call_args.kwargs["allow_agent"])
            publisher.upload(files[0], "/store")
            sftp = client.open_sftp.return_value
            sftp.posix_rename.assert_called_once()
            self.assertEqual(sftp.posix_rename.call_args.args[1], f"/store/{files[0].name}")
            publisher.close()


if __name__ == "__main__":
    unittest.main()
